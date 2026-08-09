package balbucio.keycloak.cache.redis.userSession;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.UserSessionProviderFactory;

@AutoService(UserSessionProviderFactory.class)
public class RedisUserSessionProviderFactory
        implements UserSessionProviderFactory<UserSessionProvider>, IsSupported {

    private int startupTime;

    @Override
    public UserSessionProvider create(KeycloakSession session) {
        return new RedisUserSessionProvider(session, startupTime);
    }

    @Override
    public void init(Config.Scope config) {
        startupTime = (int) (System.currentTimeMillis() / 1000);
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
        // no-op — offline sessions live in Redis; no DB preload
    }
}
