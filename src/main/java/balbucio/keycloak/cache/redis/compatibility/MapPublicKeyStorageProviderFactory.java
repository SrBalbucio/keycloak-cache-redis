package balbucio.keycloak.cache.redis.compatibility;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.service.AutoService;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.keys.PublicKeyStorageProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;

/**
 * Factory for {@link RedisPublicKeyStorageProvider}. Keeps the historical class name for SPI
 * registration stability; implementation is Redis L2 + local L1.
 */
@AutoService(PublicKeyStorageProviderFactory.class)
public class MapPublicKeyStorageProviderFactory
        implements PublicKeyStorageProviderFactory<PublicKeyStorageProvider>, IsSupported {

    private static final Logger LOG = Logger.getLogger(MapPublicKeyStorageProviderFactory.class);

    public static final String CONFIG_TTL_SECONDS = "ttlSeconds";
    public static final long DEFAULT_TTL_SECONDS = 3600L;

    private RedisPublicKeyStorageProvider provider;
    private ObjectMapper objectMapper;
    private long ttlSeconds = DEFAULT_TTL_SECONDS;
    private final Map<String, PublicKeysWrapper> sharedL1 = new ConcurrentHashMap<>();
    private StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile boolean pubsubInitialized;

    @Override
    public PublicKeyStorageProvider create(KeycloakSession session) {
        if (provider == null) {
            RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
            provider = new RedisPublicKeyStorageProvider(redis, objectMapper, ttlSeconds, sharedL1);
        }
        if (!pubsubInitialized) {
            initPubSub(session);
        }
        return provider;
    }

    private synchronized void initPubSub(KeycloakSession session) {
        if (pubsubInitialized) {
            return;
        }
        try {
            RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
            if (redis == null) {
                pubsubInitialized = true;
                return;
            }
            if (provider == null) {
                provider = new RedisPublicKeyStorageProvider(redis, objectMapper, ttlSeconds, sharedL1);
            }
            String channel = RedisKeySpace.key(RedisPublicKeyStorageProvider.INVALIDATION_CHANNEL_RELATIVE);
            subscriber = redis.connectPubSub();
            RedisPublicKeyStorageProvider target = provider;
            subscriber.addListener(
                    new RedisPubSubAdapter<String, String>() {
                        @Override
                        public void message(String ch, String message) {
                            if (channel.equals(ch)) {
                                target.clearLocal(message);
                            }
                        }
                    });
            subscriber.sync().subscribe(channel);
            LOG.infof("Public-key L1 PUBSUB subscriber initialized (channel=%s, ttl=%ds)", channel, ttlSeconds);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to init public-key PUBSUB — L1 will not receive cross-node invalidation");
        } finally {
            pubsubInitialized = true;
        }
    }

    @Override
    public void init(Config.Scope config) {
        ttlSeconds = config.getLong(CONFIG_TTL_SECONDS, DEFAULT_TTL_SECONDS);
        objectMapper = new ObjectMapper();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (subscriber != null) {
            try {
                subscriber.close();
            } catch (Exception ignored) {
                // ignore
            }
            subscriber = null;
        }
        if (provider != null) {
            provider.close();
            provider = null;
        }
        sharedL1.clear();
    }

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
                .name(CONFIG_TTL_SECONDS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .helpText("TTL in seconds for public keys stored in Redis (default 3600)")
                .defaultValue(Long.toString(DEFAULT_TTL_SECONDS))
                .add()
                .build();
    }
}
