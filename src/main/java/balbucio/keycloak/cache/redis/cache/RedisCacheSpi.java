package balbucio.keycloak.cache.redis.cache;

import com.google.auto.service.AutoService;
import org.keycloak.provider.Spi;

/**
 * Public SPI that lets other Keycloak extensions share this extension's Redis as a
 * namespaced cache (String/JSON values, TTL, optional node-local LRU with cross-node
 * invalidation, fail-open reads).
 *
 * <p>Lookup: {@code session.getProvider(RedisCacheProvider.class).getCache("my-namespace")}.
 * See {@code docs/cache-api.md}.
 */
@AutoService(Spi.class)
public class RedisCacheSpi implements Spi {

    public static final String NAME = "redisCache";

    @Override
    public boolean isInternal() {
        // Public on purpose: third-party extensions are invited to build on this API.
        return false;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Class<RedisCacheProvider> getProviderClass() {
        return RedisCacheProvider.class;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Class<RedisCacheProviderFactory> getProviderFactoryClass() {
        return RedisCacheProviderFactory.class;
    }
}
