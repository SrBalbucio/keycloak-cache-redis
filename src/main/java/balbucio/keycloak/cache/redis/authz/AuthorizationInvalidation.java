package balbucio.keycloak.cache.redis.authz;

import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;

/**
 * Implements the double-INCR invalidation pattern from the spec: bump the resource-server
 * generation before a write and again after the transaction commits. This covers the race where a
 * concurrent reader reloads from JPA between the pre-write bump and the commit.
 */
public final class AuthorizationInvalidation {

    private AuthorizationInvalidation() {}

    /**
     * Pre-write invalidation + post-commit re-invalidation.
     *
     * @param session the current Keycloak session (for transaction enlistment)
     * @param cache the Redis authorization cache
     * @param resourceServerId the resource server being mutated
     */
    public static void invalidate(KeycloakSession session, RedisAuthorizationCache cache, String resourceServerId) {
        if (resourceServerId == null || cache == null) {
            return;
        }
        // Pre-write: invalidate existing entries immediately.
        cache.invalidate(resourceServerId);

        // Post-commit: re-invalidate to cover concurrent repopulation during the tx.
        KeycloakTransactionManager tm = session.getTransactionManager();
        if (tm != null && tm.isActive()) {
            tm.enlistAfterCompletion(new AbstractKeycloakTransaction() {
                @Override
                protected void commitImpl() {
                    cache.invalidate(resourceServerId);
                }

                @Override
                protected void rollbackImpl() {
                    // no-op — the pre-write invalidation is harmless if the tx rolls back
                }
            });
        } else {
            // No active transaction — just invalidate immediately.
            cache.invalidate(resourceServerId);
        }
    }
}
