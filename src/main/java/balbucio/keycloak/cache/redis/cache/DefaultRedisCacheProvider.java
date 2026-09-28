package balbucio.keycloak.cache.redis.cache;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;

/**
 * Per-session {@link RedisCacheProvider}. Namespace LRU maps and the PUBSUB subscriber live in
 * the {@link DefaultRedisCacheProviderFactory} (node-shared); views are cheap to create.
 */
final class DefaultRedisCacheProvider implements RedisCacheProvider {

    private final DefaultRedisCacheProviderFactory factory;
    private final RedisConnectionProvider connection;

    DefaultRedisCacheProvider(
            DefaultRedisCacheProviderFactory factory, RedisConnectionProvider connection) {
        this.factory = factory;
        this.connection = connection;
    }

    @Override
    public RedisCache getCache(String namespace, RedisCacheConfig config) {
        DefaultRedisCache.validateNamespace(namespace);
        if (config == null) {
            config = RedisCacheConfig.defaults();
        }
        Map<String, DefaultRedisCache.LocalValue> lru =
                config.isLruEnabled() ? factory.lruFor(namespace, config.getLruMaxSize()) : null;
        return new DefaultRedisCache(
                namespace, config, connection, factory.objectMapper(), lru);
    }

    @Override
    public void close() {
        // Nothing session-scoped: LRU maps and subscriber belong to the factory.
    }
}
