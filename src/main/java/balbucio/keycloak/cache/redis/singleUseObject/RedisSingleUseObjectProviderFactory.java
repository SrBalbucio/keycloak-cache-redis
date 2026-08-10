package balbucio.keycloak.cache.redis.singleUseObject;

import java.util.Collections;
import java.util.Map;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import com.google.auto.service.AutoService;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.SingleUseObjectProviderFactory;
import org.keycloak.models.session.RevokedToken;
import org.keycloak.models.session.RevokedTokenPersisterProvider;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

@AutoService(SingleUseObjectProviderFactory.class)
public class RedisSingleUseObjectProviderFactory
        implements SingleUseObjectProviderFactory<SingleUseObjectProvider>, IsSupported {

    private static final Logger LOG = Logger.getLogger(RedisSingleUseObjectProviderFactory.class);

    public static final String CONFIG_PERSIST_REVOKED_TOKENS = "persistRevokedTokens";
    public static final boolean DEFAULT_PERSIST_REVOKED_TOKENS = true;
    /** Marker key (logical) matching Infinispan {@code loaded + REVOKED_KEY}. */
    public static final String LOADED = "loaded" + SingleUseObjectProvider.REVOKED_KEY;

    private volatile boolean persistRevokedTokens = DEFAULT_PERSIST_REVOKED_TOKENS;
    private volatile boolean initialized;

    @Override
    public SingleUseObjectProvider create(KeycloakSession session) {
        initialize(session);
        return new RedisSingleUseObjectProvider(session, persistRevokedTokens);
    }

    @Override
    public void init(Config.Scope config) {
        persistRevokedTokens =
                Boolean.TRUE.equals(
                        config.getBoolean(CONFIG_PERSIST_REVOKED_TOKENS, DEFAULT_PERSIST_REVOKED_TOKENS));
    }

    private void initialize(KeycloakSession session) {
        if (!persistRevokedTokens || initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
            if (redis == null) {
                initialized = true;
                return;
            }
            String loadedKey = RedisKeySpace.key("single-use:" + LOADED);
            try {
                Long exists = redis.sync().exists(loadedKey);
                if (exists == null || exists == 0L) {
                    RevokedTokenPersisterProvider persister =
                            session.getProvider(RevokedTokenPersisterProvider.class);
                    if (persister != null) {
                        RedisSingleUseObjectProvider suo =
                                new RedisSingleUseObjectProvider(session, false);
                        long now = Time.currentTime();
                        persister
                                .getAllRevokedTokens()
                                .forEach(
                                        (RevokedToken revoked) -> {
                                            long lifespanSeconds = revoked.expiry() - now;
                                            if (lifespanSeconds > 0) {
                                                suo.putRedisOnly(
                                                        revoked.tokenId()
                                                                + SingleUseObjectProvider.REVOKED_KEY,
                                                        lifespanSeconds,
                                                        Collections.emptyMap());
                                            }
                                        });
                        LOG.info("Preloaded revoked tokens from JPA into Redis");
                    }
                    redis.sync().set(loadedKey, "1", SetArgs.Builder.ex(Time.toMillis(365L * 24 * 3600)));
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to preload revoked tokens into Redis");
            }
            initialized = true;
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (!persistRevokedTokens) {
            return;
        }
        factory.register(
                event -> {
                    if (event instanceof PostMigrationEvent pme) {
                        try (KeycloakSession session = pme.getFactory().create()) {
                            session.getTransactionManager().begin();
                            try {
                                initialize(session);
                                session.getTransactionManager().commit();
                            } catch (RuntimeException ex) {
                                session.getTransactionManager().rollback();
                                throw ex;
                            }
                        }
                    }
                });
    }

    @Override
    public void close() {}

    @Override
    public String getId() {
        return Constants.INFINISPAN_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }

    @Override
    public java.util.List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(CONFIG_PERSIST_REVOKED_TOKENS)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .helpText("Persist revoked tokens to the database and preload them into Redis on startup")
                .defaultValue(Boolean.toString(DEFAULT_PERSIST_REVOKED_TOKENS))
                .add()
                .build();
    }

    // visible for tests
    void resetInitializedForTests() {
        initialized = false;
    }
}
