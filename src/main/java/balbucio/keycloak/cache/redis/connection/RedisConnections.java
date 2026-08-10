package balbucio.keycloak.cache.redis.connection;

import org.keycloak.models.KeycloakSession;

/** Lookup helpers for named Redis connections with fallback to {@code default}. */
public final class RedisConnections {

    private RedisConnections() {}

    /**
     * Returns the {@code authz} connection when configured; otherwise the default connection used
     * by sessions/cluster.
     */
    public static RedisConnectionProvider forAuthz(KeycloakSession session) {
        RedisConnectionProvider authz =
                session.getProvider(RedisConnectionProvider.class, AuthzRedisConnectionProviderFactory.PROVIDER_ID);
        if (authz != null) {
            return authz;
        }
        return session.getProvider(RedisConnectionProvider.class);
    }
}
