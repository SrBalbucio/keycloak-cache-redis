package balbucio.keycloak.cache.redis.cache;

import org.keycloak.provider.Provider;

/**
 * Entry point of the {@code redisCache} SPI. Namespace-bound caches isolate consumers from
 * each other (keys, LRU and invalidation channels are all namespaced).
 */
public interface RedisCacheProvider extends Provider {

    /**
     * Returns the cache for {@code namespace} with default settings
     * ({@link RedisCacheConfig#defaults()}).
     *
     * @throws IllegalArgumentException on blank or unsafe namespace
     */
    default RedisCache getCache(String namespace) {
        return getCache(namespace, RedisCacheConfig.defaults());
    }

    /**
     * Returns the cache for {@code namespace} with explicit settings.
     *
     * @throws IllegalArgumentException on blank or unsafe namespace
     */
    RedisCache getCache(String namespace, RedisCacheConfig config);
}
