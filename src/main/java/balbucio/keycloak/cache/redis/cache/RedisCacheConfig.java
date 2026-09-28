package balbucio.keycloak.cache.redis.cache;

/**
 * Immutable settings of a {@link RedisCache} view. Start from {@link #defaults()} and chain
 * {@code with*} overrides.
 */
public final class RedisCacheConfig {

    /** Default TTL (seconds) applied by helpers that do not take an explicit TTL. */
    public static final long DEFAULT_TTL_SECONDS = 300L;
    /** Default node-local LRU bound. */
    public static final int DEFAULT_LRU_MAX_SIZE = 1000;
    /**
     * Max lifetime (seconds) of a node-local LRU entry, whatever the Redis TTL is. Bounds
     * cross-node staleness when an invalidation broadcast is missed.
     */
    public static final long DEFAULT_LRU_TTL_SECONDS = 60L;

    private final long defaultTtlSeconds;
    private final boolean lruEnabled;
    private final int lruMaxSize;
    private final long lruTtlSeconds;

    private RedisCacheConfig(long defaultTtlSeconds, boolean lruEnabled, int lruMaxSize, long lruTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.lruEnabled = lruEnabled;
        this.lruMaxSize = lruMaxSize;
        this.lruTtlSeconds = lruTtlSeconds;
    }

    public static RedisCacheConfig defaults() {
        return new RedisCacheConfig(DEFAULT_TTL_SECONDS, true, DEFAULT_LRU_MAX_SIZE, DEFAULT_LRU_TTL_SECONDS);
    }

    public RedisCacheConfig withDefaultTtlSeconds(long defaultTtlSeconds) {
        return new RedisCacheConfig(defaultTtlSeconds, lruEnabled, lruMaxSize, lruTtlSeconds);
    }

    public RedisCacheConfig withLruEnabled(boolean lruEnabled) {
        return new RedisCacheConfig(defaultTtlSeconds, lruEnabled, lruMaxSize, lruTtlSeconds);
    }

    public RedisCacheConfig withLruMaxSize(int lruMaxSize) {
        return new RedisCacheConfig(defaultTtlSeconds, lruEnabled, lruMaxSize, lruTtlSeconds);
    }

    public RedisCacheConfig withLruTtlSeconds(long lruTtlSeconds) {
        return new RedisCacheConfig(defaultTtlSeconds, lruEnabled, lruMaxSize, lruTtlSeconds);
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
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
}
