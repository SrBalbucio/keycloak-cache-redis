package balbucio.keycloak.cache.redis.authz;

import com.fasterxml.jackson.databind.ObjectMapper;

import balbucio.keycloak.cache.redis.authz.cache.LocalAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.permissionTicket.RedisCachedPermissionTicketStore;
import balbucio.keycloak.cache.redis.authz.policy.RedisCachedPolicyStore;
import balbucio.keycloak.cache.redis.authz.resource.RedisCachedResourceStore;
import balbucio.keycloak.cache.redis.authz.resourceServer.RedisCachedResourceServerStore;
import balbucio.keycloak.cache.redis.authz.scope.RedisCachedScopeStore;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.cache.authorization.CachedStoreFactoryProvider;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.authorization.store.StoreFactory;

import java.util.Map;

/**
 * {@link CachedStoreFactoryProvider} backed by Redis cache-aside.
 *
 * <p>All five stores wrap their JPA delegates with Redis-cached decorators:
 * <ul>
 *   <li>{@link ResourceStore} — findById / findByName cached
 *   <li>{@link ResourceServerStore} — findById / findByClient cached
 *   <li>{@link ScopeStore} — findById / findByName cached
 *   <li>{@link PolicyStore} — findById / findByResource cached
 *   <li>{@link PermissionTicketStore} — findById cached (short TTL)
 * </ul>
 *
 * <p>When {@link AuthorizationCacheConfig#isEnabled()} is {@code false}, all stores delegate directly
 * to JPA, preserving the behavior of the former {@code NullCachedStoreProviderFactory}.
 */
public class RedisCachedStoreFactoryProvider implements CachedStoreFactoryProvider {

    private final KeycloakSession session;
    private final StoreFactory delegate;
    private final AuthorizationCacheConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, LocalAuthorizationCache.LocalEntry> sharedLocalLru;

    private RedisAuthorizationCache cache;

    public RedisCachedStoreFactoryProvider(
            KeycloakSession session,
            StoreFactory delegate,
            AuthorizationCacheConfig config,
            ObjectMapper objectMapper,
            Map<String, LocalAuthorizationCache.LocalEntry> sharedLocalLru) {
        this.session = session;
        this.delegate = delegate;
        this.config = config;
        this.objectMapper = objectMapper;
        this.sharedLocalLru = sharedLocalLru;
    }

    /**
     * Lazily creates the {@link RedisAuthorizationCache} (or {@link LocalAuthorizationCache} when the
     * local LRU is enabled). Returns {@code null} when caching is disabled.
     */
    protected RedisAuthorizationCache cache() {
        if (!config.isEnabled()) {
            return null;
        }
        if (cache == null) {
            RedisConnectionProvider connection = session.getProvider(RedisConnectionProvider.class);
            if (config.isLruEnabled() && sharedLocalLru != null) {
                cache = new LocalAuthorizationCache(connection, objectMapper, config, sharedLocalLru);
            } else {
                cache = new RedisAuthorizationCache(connection, objectMapper, config);
            }
        }
        return cache;
    }

    @Override
    public ResourceStore getResourceStore() {
        RedisAuthorizationCache authzCache = cache();
        if (authzCache == null) {
            return delegate.getResourceStore();
        }
        return new RedisCachedResourceStore(session, delegate.getResourceStore(), authzCache);
    }

    @Override
    public ResourceServerStore getResourceServerStore() {
        RedisAuthorizationCache authzCache = cache();
        if (authzCache == null) {
            return delegate.getResourceServerStore();
        }
        return new RedisCachedResourceServerStore(session, delegate.getResourceServerStore(), authzCache);
    }

    @Override
    public ScopeStore getScopeStore() {
        RedisAuthorizationCache authzCache = cache();
        if (authzCache == null) {
            return delegate.getScopeStore();
        }
        return new RedisCachedScopeStore(session, delegate.getScopeStore(), authzCache);
    }

    @Override
    public PolicyStore getPolicyStore() {
        RedisAuthorizationCache authzCache = cache();
        if (authzCache == null) {
            return delegate.getPolicyStore();
        }
        return new RedisCachedPolicyStore(session, delegate.getPolicyStore(), authzCache);
    }

    @Override
    public PermissionTicketStore getPermissionTicketStore() {
        RedisAuthorizationCache authzCache = cache();
        if (authzCache == null) {
            return delegate.getPermissionTicketStore();
        }
        return new RedisCachedPermissionTicketStore(
                session, delegate.getPermissionTicketStore(), authzCache);
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        delegate.setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
