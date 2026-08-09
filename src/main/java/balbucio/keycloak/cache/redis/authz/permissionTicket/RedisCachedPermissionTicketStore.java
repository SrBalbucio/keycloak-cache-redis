package balbucio.keycloak.cache.redis.authz.permissionTicket;

import java.util.List;
import java.util.Map;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.AuthorizationCacheKey;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedPermissionTicket;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.models.KeycloakSession;

/**
 * Cache-aside {@link PermissionTicketStore} wrapping the JPA delegate.
 *
 * <p>Only {@code findById} is cached, with a short TTL (permission tickets have high churn in UMA
 * flows). All find/count/query methods delegate directly to JPA (dynamic, paginated). {@code create}
 * and {@code delete} trigger generation invalidation.
 */
public class RedisCachedPermissionTicketStore implements PermissionTicketStore {

    private final KeycloakSession session;
    private final PermissionTicketStore delegate;
    private final RedisAuthorizationCache cache;

    public RedisCachedPermissionTicketStore(
            KeycloakSession session, PermissionTicketStore delegate, RedisAuthorizationCache cache) {
        this.session = session;
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public PermissionTicket create(
            ResourceServer resourceServer, Resource resource, Scope scope, String owner) {
        PermissionTicket ticket = delegate.create(resourceServer, resource, scope, owner);
        if (resourceServer != null) {
            AuthorizationInvalidation.invalidate(session, cache, resourceServer.getId());
        }
        return ticket;
    }

    @Override
    public void delete(String id) {
        cache.remove(AuthorizationCacheKey.permissionTicketById(id));
        delegate.delete(id);
    }

    @Override
    public PermissionTicket findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        String rsId = resourceServer != null ? resourceServer.getId() : null;
        long gen = cache.currentGeneration(rsId);

        CachedPermissionTicket cached =
                cache.get(
                        AuthorizationCacheKey.permissionTicketById(id),
                        gen,
                        CachedPermissionTicket.class);
        if (cached != null) {
            return new PermissionTicketAdapter(session, cached, resourceServer, delegate, cache);
        }

        PermissionTicket ticket = delegate.findById(resourceServer, id);
        if (ticket == null) {
            return null;
        }

        CachedPermissionTicket snapshot = CachedPermissionTicket.from(ticket);
        cache.put(
                AuthorizationCacheKey.permissionTicketById(id),
                gen,
                snapshot,
                cache.config().getPermissionTicketTtlSeconds());
        return new PermissionTicketAdapter(session, snapshot, resourceServer, delegate, cache);
    }

    // ---- Below: pure delegation (dynamic queries, not cached) ----

    @Override
    public long count(ResourceServer resourceServer, Map<PermissionTicket.FilterOption, String> attributes) {
        return delegate.count(resourceServer, attributes);
    }

    @Override
    public List<PermissionTicket> findByResource(ResourceServer resourceServer, Resource resource) {
        return delegate.findByResource(resourceServer, resource);
    }

    @Override
    public List<PermissionTicket> findByScope(ResourceServer resourceServer, Scope scope) {
        return delegate.findByScope(resourceServer, scope);
    }

    @Override
    public List<PermissionTicket> find(
            ResourceServer resourceServer,
            Map<PermissionTicket.FilterOption, String> attributes,
            Integer firstResult,
            Integer maxResults) {
        return delegate.find(resourceServer, attributes, firstResult, maxResults);
    }

    @Override
    public List<PermissionTicket> findGranted(ResourceServer resourceServer, String ownerId) {
        return delegate.findGranted(resourceServer, ownerId);
    }

    @Override
    public List<PermissionTicket> findGranted(ResourceServer resourceServer, String ownerId, String requester) {
        return delegate.findGranted(resourceServer, ownerId, requester);
    }

    @Override
    public List<Resource> findGrantedResources(
            String name, String start, Integer firstResult, Integer maxResults) {
        return delegate.findGrantedResources(name, start, firstResult, maxResults);
    }

    @Override
    public List<Resource> findGrantedOwnerResources(String owner, Integer firstResult, Integer maxResults) {
        return delegate.findGrantedOwnerResources(owner, firstResult, maxResults);
    }
}
