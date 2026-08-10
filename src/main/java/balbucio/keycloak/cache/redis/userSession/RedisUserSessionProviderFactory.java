package balbucio.keycloak.cache.redis.userSession;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import com.google.auto.service.AutoService;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.UserSessionProviderFactory;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

@AutoService(UserSessionProviderFactory.class)
public class RedisUserSessionProviderFactory
        implements UserSessionProviderFactory<UserSessionProvider>, IsSupported {

    private static final Logger LOG = Logger.getLogger(RedisUserSessionProviderFactory.class);

    public static final String CONFIG_PERSIST_OFFLINE_SESSIONS = "persistOfflineSessions";
    public static final boolean DEFAULT_PERSIST_OFFLINE_SESSIONS = false;
    public static final String OFFLINE_LOADED_MARKER = "user-session:offline:loaded";

    private int startupTime;
    private volatile boolean persistOfflineSessions = DEFAULT_PERSIST_OFFLINE_SESSIONS;

    @Override
    public UserSessionProvider create(KeycloakSession session) {
        return new RedisUserSessionProvider(session, startupTime, persistOfflineSessions);
    }

    @Override
    public void init(Config.Scope config) {
        startupTime = (int) (System.currentTimeMillis() / 1000);
        persistOfflineSessions =
                Boolean.TRUE.equals(
                        config.getBoolean(
                                CONFIG_PERSIST_OFFLINE_SESSIONS, DEFAULT_PERSIST_OFFLINE_SESSIONS));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

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
    public void loadPersistentSessions(
            KeycloakSessionFactory sessionFactory, int maxErrors, int sessionsPerSegment) {
        if (!persistOfflineSessions) {
            return;
        }
        try (KeycloakSession session = sessionFactory.create()) {
            session.getTransactionManager().begin();
            try {
                preloadOfflineSessions(session, sessionsPerSegment);
                session.getTransactionManager().commit();
            } catch (RuntimeException ex) {
                session.getTransactionManager().rollback();
                throw ex;
            }
        }
    }

    private void preloadOfflineSessions(KeycloakSession session, int sessionsPerSegment) {
        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (redis == null) {
            return;
        }
        String loadedKey = RedisKeySpace.key(OFFLINE_LOADED_MARKER);
        try {
            Long exists = redis.sync().exists(loadedKey);
            if (exists != null && exists > 0L) {
                return;
            }
            UserSessionPersisterProvider persister =
                    session.getProvider(UserSessionPersisterProvider.class);
            if (persister == null) {
                redis.sync().set(loadedKey, "1", SetArgs.Builder.ex(Time.toMillis(365L * 24 * 3600)));
                return;
            }
            RedisUserSessionProvider provider =
                    new RedisUserSessionProvider(session, startupTime, false);
            int batch = sessionsPerSegment <= 0 ? 100 : sessionsPerSegment;
            int first = 0;
            while (true) {
                List<UserSessionModel> batchSessions;
                try (Stream<UserSessionModel> stream =
                        persister.loadUserSessionsStream(first, batch, true, "")) {
                    batchSessions = stream.toList();
                }
                if (batchSessions.isEmpty()) {
                    break;
                }
                for (UserSessionModel persisted : batchSessions) {
                    importOfflineSession(session, provider, persister, persisted);
                }
                first += batchSessions.size();
                if (batchSessions.size() < batch) {
                    break;
                }
            }
            redis.sync().set(loadedKey, "1", SetArgs.Builder.ex(Time.toMillis(365L * 24 * 3600)));
            LOG.infof("Preloaded offline sessions from JPA into Redis (marker=%s)", loadedKey);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to preload offline sessions into Redis");
        }
    }

    private void importOfflineSession(
            KeycloakSession session,
            RedisUserSessionProvider provider,
            UserSessionPersisterProvider persister,
            UserSessionModel persisted) {
        RealmModel realm = persisted.getRealm();
        if (realm == null) {
            return;
        }
        UserSessionModel created = provider.createOfflineUserSession(persisted);
        for (Map.Entry<String, AuthenticatedClientSessionModel> e :
                persisted.getAuthenticatedClientSessions().entrySet()) {
            ClientModel client = realm.getClientById(e.getKey());
            if (client == null) {
                continue;
            }
            AuthenticatedClientSessionModel loaded =
                    persister.loadClientSession(realm, client, created, true);
            if (loaded != null) {
                provider.createOfflineClientSession(loaded, created);
            }
        }
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(CONFIG_PERSIST_OFFLINE_SESSIONS)
                .type("boolean")
                .helpText(
                        "When true, offline sessions are written through to UserSessionPersisterProvider (JPA) and preloaded into Redis on boot.")
                .defaultValue(Boolean.toString(DEFAULT_PERSIST_OFFLINE_SESSIONS))
                .add()
                .build();
    }
}
