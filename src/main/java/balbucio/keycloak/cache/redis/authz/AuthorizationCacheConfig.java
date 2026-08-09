package balbucio.keycloak.cache.redis.authz;

/**
 * Configuration for the authorization cache-aside layer.
 *
 * <p>Reads from environment variables and system properties, following the same pattern as
 * {@link balbucio.keycloak.cache.redis.common.CommunityProfiles}. When the Redis cache extension
 * is active and {@code authz.enabled} is {@code true} (default), authorization entities are cached
 * in Redis with cache-aside semantics over the JPA store.
 */
public final class AuthorizationCacheConfig {

    public static final String ENV_ENABLED = "KC_CACHE_REDIS_AUTHZ_ENABLED";
    public static final String PROP_ENABLED = "kc.cache.redis.authz.enabled";

    public static final String ENV_TTL = "KC_CACHE_REDIS_AUTHZ_TTL_SECONDS";
    public static final String PROP_TTL = "kc.cache.redis.authz.ttl-seconds";

    public static final String ENV_PT_TTL = "KC_CACHE_REDIS_AUTHZ_PERMISSION_TICKET_TTL_SECONDS";
    public static final String PROP_PT_TTL = "kc.cache.redis.authz.permission-ticket-ttl-seconds";

    public static final String ENV_GEN_TTL = "KC_CACHE_REDIS_AUTHZ_GEN_TTL_SECONDS";
    public static final String PROP_GEN_TTL = "kc.cache.redis.authz.gen-ttl-seconds";

    public static final String ENV_LRU_ENABLED = "KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_ENABLED";
    public static final String PROP_LRU_ENABLED = "kc.cache.redis.authz.local-lru.enabled";

    public static final String ENV_LRU_MAX_SIZE = "KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_MAX_SIZE";
    public static final String PROP_LRU_MAX_SIZE = "kc.cache.redis.authz.local-lru.max-size";

    public static final String ENV_LRU_TTL = "KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_TTL_SECONDS";
    public static final String PROP_LRU_TTL = "kc.cache.redis.authz.local-lru.ttl-seconds";

    static final boolean DEFAULT_ENABLED = true;
    static final long DEFAULT_TTL_SECONDS = 1800L;
    static final long DEFAULT_PT_TTL_SECONDS = 300L;
    static final long DEFAULT_GEN_TTL_SECONDS = 604800L;
    static final boolean DEFAULT_LRU_ENABLED = false;
    static final int DEFAULT_LRU_MAX_SIZE = 1000;
    static final long DEFAULT_LRU_TTL_SECONDS = 30L;

    private final boolean enabled;
    private final long ttlSeconds;
    private final long permissionTicketTtlSeconds;
    private final long genTtlSeconds;
    private final boolean lruEnabled;
    private final int lruMaxSize;
    private final long lruTtlSeconds;

    private AuthorizationCacheConfig(
            boolean enabled, long ttlSeconds, long permissionTicketTtlSeconds, long genTtlSeconds,
            boolean lruEnabled, int lruMaxSize, long lruTtlSeconds) {
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
        this.permissionTicketTtlSeconds = permissionTicketTtlSeconds;
        this.genTtlSeconds = genTtlSeconds;
        this.lruEnabled = lruEnabled;
        this.lruMaxSize = lruMaxSize;
        this.lruTtlSeconds = lruTtlSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public long getPermissionTicketTtlSeconds() {
        return permissionTicketTtlSeconds;
    }

    public long getGenTtlSeconds() {
        return genTtlSeconds;
    }

    public boolean isLruEnabled() {
        return lruEnabled;
    }

    public int getLruMaxSize() {
        return lruMaxSize;
    }

    public long getLruTtlSeconds() {
        return lruTtlSeconds;
    }

    public static AuthorizationCacheConfig load() {
        return new AuthorizationCacheConfig(
                readBool(ENV_ENABLED, PROP_ENABLED, DEFAULT_ENABLED),
                readLong(ENV_TTL, PROP_TTL, DEFAULT_TTL_SECONDS),
                readLong(ENV_PT_TTL, PROP_PT_TTL, DEFAULT_PT_TTL_SECONDS),
                readLong(ENV_GEN_TTL, PROP_GEN_TTL, DEFAULT_GEN_TTL_SECONDS),
                readBool(ENV_LRU_ENABLED, PROP_LRU_ENABLED, DEFAULT_LRU_ENABLED),
                readInt(ENV_LRU_MAX_SIZE, PROP_LRU_MAX_SIZE, DEFAULT_LRU_MAX_SIZE),
                readLong(ENV_LRU_TTL, PROP_LRU_TTL, DEFAULT_LRU_TTL_SECONDS));
    }

    private static boolean readBool(String env, String prop, boolean fallback) {
        String envVal = System.getenv(env);
        if (envVal != null && !envVal.isBlank()) {
            return Boolean.parseBoolean(envVal.trim());
        }
        return Boolean.parseBoolean(System.getProperty(prop, Boolean.toString(fallback)));
    }

    private static long readLong(String env, String prop, long fallback) {
        String envVal = System.getenv(env);
        if (envVal != null && !envVal.isBlank()) {
            return parseLong(envVal.trim(), fallback);
        }
        return parseLong(System.getProperty(prop, Long.toString(fallback)), fallback);
    }

    private static int readInt(String env, String prop, int fallback) {
        String envVal = System.getenv(env);
        if (envVal != null && !envVal.isBlank()) {
            return parseInt(envVal.trim(), fallback);
        }
        return parseInt(System.getProperty(prop, Integer.toString(fallback)), fallback);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
