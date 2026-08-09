package balbucio.keycloak.cache.redis.common;

/**
 * Thrown when a Redis-backed model entity is used after deletion or invalidation.
 */
public class ModelIllegalStateException extends IllegalStateException {

    public ModelIllegalStateException(String message) {
        super(message);
    }
}
