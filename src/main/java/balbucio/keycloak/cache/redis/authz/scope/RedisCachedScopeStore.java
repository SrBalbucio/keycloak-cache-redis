package balbucio.keycloak.cache.redis.authz.scope;

import java.util.List;
import java.util.Map;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.AuthorizationCacheKey;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedScope;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.KeycloakSession;

/**
 * Cache-aside {@link ScopeStore} wrapping the JPA delegate.
 *
 * <p>Only {@code findById} and {@code findByName} are cached. {@code findByResourceServer} and the
 * filtered variant delegate directly to JPA (Admin Console paginated queries).
 */
public class RedisCachedScopeStore implements ScopeStore {

    private final KeycloakSession session;
    private final ScopeStore delegate;
    private final RedisAuthorizationCache cache;

    public RedisCachedScopeStore(
            KeycloakSession session, ScopeStore delegate, RedisAuthorizationCache cache) {
        this.session = session;
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public Scope create(ResourceServer resourceServer, String id, String name) {
        Scope scope = delegate.create(resourceServer, id, name);
        if (resourceServer != null) {
            AuthorizationInvalidation.invalidate(session, cache, resourceServer.getId());
        }
        return scope;
    }

    @Override
    public void delete(String id) {
        cache.remove(AuthorizationCacheKey.scopeById(id));
        delegate.delete(id);
    }

    @Override
    public Scope findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        String rsId = resourceServer != null ? resourceServer.getId() : null;
        long gen = cache.currentGeneration(rsId);

        CachedScope cached = cache.get(AuthorizationCacheKey.scopeById(id), gen, CachedScope.class);
        if (cached != null) {
            return new ScopeAdapter(session, cached, resourceServer, delegate, cache);
        }

        Scope scope = delegate.findById(resourceServer, id);
        if (scope == null) {
            return null;
        }

        CachedScope snapshot = CachedScope.from(scope);
        cache.put(AuthorizationCacheKey.scopeById(id), gen, snapshot);
        return new ScopeAdapter(session, snapshot, resourceServer, delegate, cache);
    }

    @Override
    public Scope findByName(ResourceServer resourceServer, String name) {
        if (name == null || resourceServer == null) {
            return delegate.findByName(resourceServer, name);
        }
        String rsId = resourceServer.getId();
        long gen = cache.currentGeneration(rsId);

        CachedScope cached =
                cache.get(AuthorizationCacheKey.scopeByName(rsId, name), gen, CachedScope.class);
        if (cached != null) {
            return new ScopeAdapter(session, cached, resourceServer, delegate, cache);
        }

        Scope scope = delegate.findByName(resourceServer, name);
        if (scope == null) {
            return null;
        }

        CachedScope snapshot = CachedScope.from(scope);
        cache.put(AuthorizationCacheKey.scopeByName(rsId, name), gen, snapshot);
        return new ScopeAdapter(session, snapshot, resourceServer, delegate, cache);
    }

    @Override
    public List<Scope> findByResourceServer(ResourceServer resourceServer) {
        return delegate.findByResourceServer(resourceServer);
    }

    @Override
    public List<Scope> findByResourceServer(
            ResourceServer resourceServer,
            Map<Scope.FilterOption, String[]> attributes,
            Integer firstResult,
            Integer maxResults) {
        return delegate.findByResourceServer(resourceServer, attributes, firstResult, maxResults);
    }
}
