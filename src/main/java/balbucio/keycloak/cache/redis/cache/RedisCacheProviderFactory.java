package balbucio.keycloak.cache.redis.cache;

import org.keycloak.provider.ProviderFactory;

/** Factory contract of the {@code redisCache} SPI. Implementation id is {@code "redis"}. */
public interface RedisCacheProviderFactory extends ProviderFactory<RedisCacheProvider> {}
