package balbucio.keycloak.cache.redis.connection;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@AutoService(RedisConnectionProviderFactory.class)
public class DefaultRedisConnectionProviderFactory implements RedisConnectionProviderFactory, IsSupported {

    private LettuceRedisClientSupport support;

    @Override
    public RedisConnectionProvider create(KeycloakSession session) {
        return support.asProvider();
    }

    @Override
    public void init(Config.Scope config) {
        support = new LettuceRedisClientSupport(true);
        // Preserve previous default when SPI nodes omitted
        if (config.get("nodes") == null || config.get("nodes").isBlank()) {
            Config.Scope withDefault =
                    new OverlayScope(config, "nodes", "redis:6379");
            support.init(withDefault, "KC_SPI_REDIS_CONNECTION_DEFAULT");
        } else {
            support.init(config, "KC_SPI_REDIS_CONNECTION_DEFAULT");
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (support != null) {
            support.close();
            support = null;
        }
    }

    @Override
    public String getId() {
        return Constants.DEFAULT_PROVIDER_ID;
    }

    /** Minimal Config.Scope overlay for a single defaulted key. */
    private static final class OverlayScope implements Config.Scope {
        private final Config.Scope delegate;
        private final String key;
        private final String value;

        OverlayScope(Config.Scope delegate, String key, String value) {
            this.delegate = delegate;
            this.key = key;
            this.value = value;
        }

        @Override
        public String get(String name) {
            if (key.equals(name)) {
                String v = delegate.get(name);
                return v == null || v.isBlank() ? value : v;
            }
            return delegate.get(name);
        }

        @Override
        public String get(String name, String defaultValue) {
            String v = get(name);
            return v != null ? v : defaultValue;
        }

        @Override
        public String[] getArray(String name) {
            return delegate.getArray(name);
        }

        @Override
        public Integer getInt(String name, Integer defaultValue) {
            return delegate.getInt(name, defaultValue);
        }

        @Override
        public Long getLong(String name, Long defaultValue) {
            return delegate.getLong(name, defaultValue);
        }

        @Override
        public Boolean getBoolean(String name, Boolean defaultValue) {
            return delegate.getBoolean(name, defaultValue);
        }

        @Override
        public Config.Scope scope(String... scope) {
            return delegate.scope(scope);
        }

        @Override
        public java.util.Set<String> getPropertyNames() {
            return delegate.getPropertyNames();
        }

        @Override
        public Config.Scope root() {
            return delegate.root();
        }
    }
}
