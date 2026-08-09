package balbucio.keycloak.cache.redis.common;

/**
 * Global Redis key prefix applied to every key written by this extension.
 *
 * <p>Configured via SPI {@code keyPrefix} ({@code KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX})
 * or env {@code KC_REDIS_KEY_PREFIX}.
 */
public final class RedisKeySpace {

    public static final String ENV_KEY_PREFIX = "KC_REDIS_KEY_PREFIX";

    private static volatile String prefix = "";

    private RedisKeySpace() {}

    public static void configure(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) {
            prefix = "";
            return;
        }
        String normalized = rawPrefix.trim();
        if (!normalized.endsWith(":")) {
            normalized = normalized + ":";
        }
        prefix = normalized;
    }

    public static String prefix() {
        return prefix;
    }

    public static String key(String relative) {
        if (relative == null) {
            return prefix;
        }
        return prefix + relative;
    }
}
