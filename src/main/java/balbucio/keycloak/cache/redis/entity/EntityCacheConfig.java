package balbucio.keycloak.cache.redis.entity;

/**
 * Feature flag and TTL for the Redis entity-cache MVP (user/realm/client lookup indexes).
 *
 * <p>Env: {@code KC_CACHE_REDIS_ENTITY_ENABLED}, {@code KC_CACHE_REDIS_ENTITY_TTL_SECONDS}.
 */
public final class EntityCacheConfig {

    public static final String ENV_ENABLED = "KC_CACHE_REDIS_ENTITY_ENABLED";
    public static final String ENV_TTL_SECONDS = "KC_CACHE_REDIS_ENTITY_TTL_SECONDS";

    private final boolean enabled;
    private final long ttlSeconds;

    private EntityCacheConfig(boolean enabled, long ttlSeconds) {
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
    }

    public static EntityCacheConfig load() {
        boolean enabled = parseBool(System.getenv(ENV_ENABLED), false);
        long ttl = parseLong(System.getenv(ENV_TTL_SECONDS), 1800L);
        return new EntityCacheConfig(enabled, ttl);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    private static boolean parseBool(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "1".equals(raw) || "true".equalsIgnoreCase(raw);
    }

    private static long parseLong(String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
