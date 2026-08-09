package balbucio.keycloak.cache.redis.common;

/**
 * Entities that carry an absolute expiration timestamp (epoch millis).
 */
public interface ExpirableEntity {

    Long getExpiration();

    void setExpiration(Long expiration);

    default boolean isExpired(long nowMillis) {
        Long expiration = getExpiration();
        return expiration != null && expiration > 0 && expiration <= nowMillis;
    }
}
