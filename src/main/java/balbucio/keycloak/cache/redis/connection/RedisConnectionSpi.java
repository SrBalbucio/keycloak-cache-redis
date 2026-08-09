package balbucio.keycloak.cache.redis.connection;

import com.google.auto.service.AutoService;
import org.keycloak.provider.Spi;

@AutoService(Spi.class)
public class RedisConnectionSpi implements Spi {

    public static final String NAME = "redisConnection";

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Class<RedisConnectionProvider> getProviderClass() {
        return RedisConnectionProvider.class;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Class<RedisConnectionProviderFactory> getProviderFactoryClass() {
        return RedisConnectionProviderFactory.class;
    }
}
