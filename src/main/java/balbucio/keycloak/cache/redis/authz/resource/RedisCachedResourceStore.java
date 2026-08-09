package balbucio.keycloak.cache.redis.authz.resource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.AuthorizationCacheKey;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedResource;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.models.KeycloakSession;

/**
 * Cache-aside {@link ResourceStore} wrapping the JPA delegate.
 *
 * <p>Only {@code findById} and {@code findByName} are cached (the hot path during policy
 * evaluation). All other methods ({@code findByOwner}, {@code findByResourceServer}, {@code find},
 * {@code findByScopes}, {@code findByType}) delegate directly to JPA — they are paginated/dynamic
 * queries used almost exclusively by the Admin Console.
 */
public class RedisCachedResourceStore implements ResourceStore {

    private final KeycloakSession session;
    private final ResourceStore delegate;
    private final RedisAuthorizationCache cache;

    public RedisCachedResourceStore(
            KeycloakSession session, ResourceStore delegate, RedisAuthorizationCache cache) {
        this.session = session;
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public Resource create(ResourceServer resourceServer, String id, String name, String owner) {
        Resource resource = delegate.create(resourceServer, id, name, owner);
        if (resourceServer != null) {
            AuthorizationInvalidation.invalidate(session, cache, resourceServer.getId());
        }
        return resource;
    }

    @Override
    public void delete(String id) {
        // Best-effort: remove the by-id entry; generation bump requires rsId (not available here).
        cache.remove(AuthorizationCacheKey.resourceById(id));
        delegate.delete(id);
    }

    @Override
    public Resource findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        String rsId = resourceServer != null ? resourceServer.getId() : null;
        long gen = cache.currentGeneration(rsId);

        CachedResource cached =
                cache.get(AuthorizationCacheKey.resourceById(id), gen, CachedResource.class);
        if (cached != null) {
            return new ResourceAdapter(session, cached, resourceServer, delegate, cache);
        }

        Resource resource = delegate.findById(resourceServer, id);
        if (resource == null) {
            return null;
        }

        CachedResource snapshot = CachedResource.from(resource);
        cache.put(AuthorizationCacheKey.resourceById(id), gen, snapshot);
        return new ResourceAdapter(session, snapshot, resourceServer, delegate, cache);
    }

    @Override
    public Resource findByName(ResourceServer resourceServer, String name, String ownerId) {
        if (name == null || resourceServer == null) {
            return delegate.findByName(resourceServer, name, ownerId);
        }
        String rsId = resourceServer.getId();
        String ownerIdKey = ownerId != null ? ownerId : rsId;
        String key = AuthorizationCacheKey.resourceByName(rsId, ownerIdKey + ":" + name);

        long gen = cache.currentGeneration(rsId);
        CachedResource cached = cache.get(key, gen, CachedResource.class);
        if (cached != null) {
            return new ResourceAdapter(session, cached, resourceServer, delegate, cache);
        }

        Resource resource = delegate.findByName(resourceServer, name, ownerId);
        if (resource == null) {
            return null;
        }

        CachedResource snapshot = CachedResource.from(resource);
        cache.put(key, gen, snapshot);
        return new ResourceAdapter(session, snapshot, resourceServer, delegate, cache);
    }

    // ---- Below: pure delegation (dynamic queries, not cached) ----

    @Override
    public List<Resource> findByOwner(ResourceServer resourceServer, String ownerId) {
        return delegate.findByOwner(resourceServer, ownerId);
    }

    @Override
    public void findByOwner(
            ResourceServer resourceServer, String ownerId, Consumer<Resource> consumer) {
        delegate.findByOwner(resourceServer, ownerId, consumer);
    }

    @Override
    public List<Resource> findByResourceServer(ResourceServer resourceServer) {
        return delegate.findByResourceServer(resourceServer);
    }

    @Override
    public List<Resource> find(
            ResourceServer resourceServer,
            Map<Resource.FilterOption, String[]> attributes,
            Integer firstResult,
            Integer maxResults) {
        return delegate.find(resourceServer, attributes, firstResult, maxResults);
    }

    @Override
    public List<Resource> findByScopes(ResourceServer resourceServer, Set<Scope> scopes) {
        return delegate.findByScopes(resourceServer, scopes);
    }

    @Override
    public void findByScopes(
            ResourceServer resourceServer, Set<Scope> scopes, Consumer<Resource> consumer) {
        delegate.findByScopes(resourceServer, scopes, consumer);
    }

    @Override
    public List<Resource> findByType(ResourceServer resourceServer, String type) {
        return delegate.findByType(resourceServer, type);
    }

    @Override
    public void findByType(
            ResourceServer resourceServer, String type, Consumer<Resource> consumer) {
        delegate.findByType(resourceServer, type, consumer);
    }

    @Override
    public void findByType(
            ResourceServer resourceServer,
            String type,
            String owner,
            Consumer<Resource> consumer) {
        delegate.findByType(resourceServer, type, owner, consumer);
    }

    @Override
    public void findByTypeInstance(
            ResourceServer resourceServer, String type, Consumer<Resource> consumer) {
        delegate.findByTypeInstance(resourceServer, type, consumer);
    }
}
