package balbucio.keycloak.cache.redis;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.storage.datastore.DefaultDatastoreProvider;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

/**
 * Forces session regions to resolve through {@link KeycloakSession#getProvider}, so Redis
 * factories (same id as Infinispan, higher order) are selected when enabled.
 */
public class RedisDatastoreProvider extends DefaultDatastoreProvider {

    private final KeycloakSession session;

    public RedisDatastoreProvider(DefaultDatastoreProviderFactory factory, KeycloakSession session) {
        super(factory, session);
        this.session = session;
    }

    @Override
    public UserSessionProvider userSessions() {
        return session.getProvider(UserSessionProvider.class);
    }

    @Override
    public AuthenticationSessionProvider authSessions() {
        return session.getProvider(AuthenticationSessionProvider.class);
    }

    @Override
    public UserLoginFailureProvider loginFailures() {
        return session.getProvider(UserLoginFailureProvider.class);
    }

    @Override
    public SingleUseObjectProvider singleUseObjects() {
        return session.getProvider(SingleUseObjectProvider.class);
    }
}
