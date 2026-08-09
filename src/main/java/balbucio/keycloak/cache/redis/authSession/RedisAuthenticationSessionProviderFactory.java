package balbucio.keycloak.cache.redis.authSession;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.AuthenticationSessionProviderFactory;

import java.util.List;

@AutoService(AuthenticationSessionProviderFactory.class)
public class RedisAuthenticationSessionProviderFactory
        implements AuthenticationSessionProviderFactory<AuthenticationSessionProvider>, IsSupported {

    public static final String AUTH_SESSIONS_LIMIT = "authSessionsLimit";
    public static final int DEFAULT_AUTH_SESSIONS_LIMIT = 300;

    private int authSessionsLimit = DEFAULT_AUTH_SESSIONS_LIMIT;

    @Override
    public AuthenticationSessionProvider create(KeycloakSession session) {
        return new RedisAuthenticationSessionProvider(session, authSessionsLimit);
    }

    @Override
    public void init(Config.Scope config) {
        int limit = config.getInt(AUTH_SESSIONS_LIMIT, DEFAULT_AUTH_SESSIONS_LIMIT);
        authSessionsLimit = limit <= 0 ? DEFAULT_AUTH_SESSIONS_LIMIT : limit;
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
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(AUTH_SESSIONS_LIMIT)
                .type("int")
                .helpText("Max authentication sessions (tabs) per root authentication session")
                .defaultValue(DEFAULT_AUTH_SESSIONS_LIMIT)
                .add()
                .build();
    }
}
