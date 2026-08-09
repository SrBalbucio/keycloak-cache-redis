package balbucio.keycloak.cache.redis.authz.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.AuthorizationCacheKey;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedPolicy;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.idm.authorization.AbstractPolicyRepresentation;

/**
 * Cache-aside {@link PolicyStore} wrapping the JPA delegate.
 *
 * <p>{@code findById} and {@code findByResource} are cached (hot paths during policy evaluation).
 * All other methods delegate directly to JPA.
 */
public class RedisCachedPolicyStore implements PolicyStore {

    private final KeycloakSession session;
    private final PolicyStore delegate;
    private final RedisAuthorizationCache cache;

    public RedisCachedPolicyStore(
            KeycloakSession session, PolicyStore delegate, RedisAuthorizationCache cache) {
        this.session = session;
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public Policy create(
            ResourceServer resourceServer, AbstractPolicyRepresentation representation) {
        Policy policy = delegate.create(resourceServer, representation);
        if (resourceServer != null) {
            AuthorizationInvalidation.invalidate(session, cache, resourceServer.getId());
        }
        return policy;
    }

    @Override
    public void delete(String id) {
        cache.remove(AuthorizationCacheKey.policyById(id));
        delegate.delete(id);
    }

    @Override
    public Policy findById(ResourceServer resourceServer, String id) {
        if (id == null) {
            return null;
        }
        String rsId = resourceServer != null ? resourceServer.getId() : null;
        long gen = cache.currentGeneration(rsId);

        CachedPolicy cached =
                cache.get(AuthorizationCacheKey.policyById(id), gen, CachedPolicy.class);
        if (cached != null) {
            return new PolicyAdapter(session, cached, resourceServer, delegate, cache);
        }

        Policy policy = delegate.findById(resourceServer, id);
        if (policy == null) {
            return null;
        }

        CachedPolicy snapshot = CachedPolicy.from(policy);
        cache.put(AuthorizationCacheKey.policyById(id), gen, snapshot);
        return new PolicyAdapter(session, snapshot, resourceServer, delegate, cache);
    }

    @Override
    public Policy findByName(ResourceServer resourceServer, String name) {
        return delegate.findByName(resourceServer, name);
    }

    @Override
    public List<Policy> findByResourceServer(ResourceServer resourceServer) {
        return delegate.findByResourceServer(resourceServer);
    }

    @Override
    public List<Policy> find(
            ResourceServer resourceServer,
            Map<Policy.FilterOption, String[]> attributes,
            Integer firstResult,
            Integer maxResults) {
        return delegate.find(resourceServer, attributes, firstResult, maxResults);
    }

    @Override
    public List<Policy> findByResource(ResourceServer resourceServer, Resource resource) {
        if (resource == null || resource.getId() == null || resourceServer == null) {
            return delegate.findByResource(resourceServer, resource);
        }
        String rsId = resourceServer.getId();
        String resId = resource.getId();
        String key = AuthorizationCacheKey.policyByResource(rsId, resId);

        long gen = cache.currentGeneration(rsId);
        CachedPolicyList cachedList = cache.get(key, gen, CachedPolicyList.class);
        if (cachedList != null && !cachedList.policyIds.isEmpty()) {
            return resolveByIds(resourceServer, cachedList.policyIds);
        }

        List<Policy> policies = delegate.findByResource(resourceServer, resource);
        if (policies == null || policies.isEmpty()) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        for (Policy p : policies) {
            if (p.getId() != null) {
                ids.add(p.getId());
            }
        }
        cache.put(key, gen, new CachedPolicyList(ids));
        return policies;
    }

    @Override
    public void findByResource(
            ResourceServer resourceServer, Resource resource, Consumer<Policy> consumer) {
        delegate.findByResource(resourceServer, resource, consumer);
    }

    @Override
    public List<Policy> findByResourceType(ResourceServer resourceServer, String resourceType) {
        return delegate.findByResourceType(resourceServer, resourceType);
    }

    @Override
    public void findByResourceType(
            ResourceServer resourceServer, String resourceType, Consumer<Policy> consumer) {
        delegate.findByResourceType(resourceServer, resourceType, consumer);
    }

    @Override
    public List<Policy> findByScopes(
            ResourceServer resourceServer, List<Scope> scopes) {
        return delegate.findByScopes(resourceServer, scopes);
    }

    @Override
    public List<Policy> findByScopes(
            ResourceServer resourceServer, Resource resource, List<Scope> scopes) {
        return delegate.findByScopes(resourceServer, resource, scopes);
    }

    @Override
    public void findByScopes(
            ResourceServer resourceServer,
            Resource resource,
            List<Scope> scopes,
            Consumer<Policy> consumer) {
        delegate.findByScopes(resourceServer, resource, scopes, consumer);
    }

    @Override
    public List<Policy> findByType(ResourceServer resourceServer, String type) {
        return delegate.findByType(resourceServer, type);
    }

    @Override
    public List<Policy> findDependentPolicies(ResourceServer resourceServer, String id) {
        return delegate.findDependentPolicies(resourceServer, id);
    }

    @Override
    public Stream<Policy> findDependentPolicies(
            ResourceServer resourceServer,
            String id,
            String serverId,
            String name,
            String type,
            String owner) {
        return delegate.findDependentPolicies(resourceServer, id, serverId, name, type, owner);
    }

    @Override
    public Stream<Policy> findDependentPolicies(
            ResourceServer resourceServer,
            String id,
            String serverId,
            String name,
            String type,
            List<String> owners) {
        return delegate.findDependentPolicies(resourceServer, id, serverId, name, type, owners);
    }

    private List<Policy> resolveByIds(ResourceServer resourceServer, List<String> ids) {
        List<Policy> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (seen.add(id)) {
                Policy p = findById(resourceServer, id);
                if (p != null) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    /**
     * Lightweight container for caching a list of policy IDs (result of {@code findByResource}).
     * Individual policies are resolved via {@code findById} which has its own cache entry.
     */
    public static class CachedPolicyList {
        public List<String> policyIds;

        public CachedPolicyList() {}

        CachedPolicyList(List<String> policyIds) {
            this.policyIds = policyIds;
        }
    }
}
