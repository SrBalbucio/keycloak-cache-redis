package balbucio.keycloak.cache.redis.authz.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedPolicy;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.cache.authorization.CachedStoreFactoryProvider;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;

/**
 * {@link Policy} backed by a {@link CachedPolicy} snapshot for reads and a lazily-loaded JPA
 * delegate for writes. Related entities (associated policies, resources, scopes) are resolved
 * lazily through the {@link CachedStoreFactoryProvider}, so each resolution benefits from the
 * cache-aside layer.
 */
public class PolicyAdapter implements Policy {

    private final KeycloakSession session;
    private final CachedPolicy cached;
    private final ResourceServer resourceServer;
    private final PolicyStore delegateStore;
    private final RedisAuthorizationCache cache;

    private Policy delegate;

    public PolicyAdapter(
            KeycloakSession session,
            CachedPolicy cached,
            ResourceServer resourceServer,
            PolicyStore delegateStore,
            RedisAuthorizationCache cache) {
        this.session = session;
        this.cached = cached;
        this.resourceServer = resourceServer;
        this.delegateStore = delegateStore;
        this.cache = cache;
    }

    public CachedPolicy getCached() {
        return cached;
    }

    @Override
    public String getId() {
        if (delegate != null) {
            return delegate.getId();
        }
        return cached.getId();
    }

    @Override
    public String getType() {
        if (delegate != null) {
            return delegate.getType();
        }
        return cached.getType();
    }

    @Override
    public DecisionStrategy getDecisionStrategy() {
        if (delegate != null) {
            return delegate.getDecisionStrategy();
        }
        return cached.getDecisionStrategy();
    }

    @Override
    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        getDelegateForUpdate().setDecisionStrategy(decisionStrategy);
    }

    @Override
    public Logic getLogic() {
        if (delegate != null) {
            return delegate.getLogic();
        }
        return cached.getLogic();
    }

    @Override
    public void setLogic(Logic logic) {
        getDelegateForUpdate().setLogic(logic);
    }

    @Override
    public Map<String, String> getConfig() {
        if (delegate != null) {
            return delegate.getConfig();
        }
        Map<String, String> config = cached.getConfig();
        return config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
    }

    @Override
    public void setConfig(Map<String, String> config) {
        getDelegateForUpdate().setConfig(config);
    }

    @Override
    public void removeConfig(String name) {
        getDelegateForUpdate().removeConfig(name);
    }

    @Override
    public void putConfig(String name, String value) {
        getDelegateForUpdate().putConfig(name, value);
    }

    @Override
    public String getName() {
        if (delegate != null) {
            return delegate.getName();
        }
        return cached.getName();
    }

    @Override
    public void setName(String name) {
        getDelegateForUpdate().setName(name);
    }

    @Override
    public String getDescription() {
        if (delegate != null) {
            return delegate.getDescription();
        }
        return cached.getDescription();
    }

    @Override
    public void setDescription(String description) {
        getDelegateForUpdate().setDescription(description);
    }

    @Override
    public ResourceServer getResourceServer() {
        if (delegate != null) {
            return delegate.getResourceServer();
        }
        return resourceServer;
    }

    @Override
    public String getOwner() {
        if (delegate != null) {
            return delegate.getOwner();
        }
        return cached.getOwner();
    }

    @Override
    public void setOwner(String owner) {
        getDelegateForUpdate().setOwner(owner);
    }

    @Override
    public Set<Policy> getAssociatedPolicies() {
        if (delegate != null) {
            return delegate.getAssociatedPolicies();
        }
        Set<String> ids = cached.getAssociatedPolicyIds();
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        return resolvePolicies(ids);
    }

    @Override
    public Set<Resource> getResources() {
        if (delegate != null) {
            return delegate.getResources();
        }
        Set<String> ids = cached.getResourceIds();
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        return resolveResources(ids);
    }

    @Override
    public Set<Scope> getScopes() {
        if (delegate != null) {
            return delegate.getScopes();
        }
        Set<String> ids = cached.getScopeIds();
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        return resolveScopes(ids);
    }

    @Override
    public void addScope(Scope scope) {
        getDelegateForUpdate().addScope(scope);
    }

    @Override
    public void removeScope(Scope scope) {
        getDelegateForUpdate().removeScope(scope);
    }

    @Override
    public void addAssociatedPolicy(Policy associatedPolicy) {
        getDelegateForUpdate().addAssociatedPolicy(associatedPolicy);
    }

    @Override
    public void removeAssociatedPolicy(Policy associatedPolicy) {
        getDelegateForUpdate().removeAssociatedPolicy(associatedPolicy);
    }

    @Override
    public void addResource(Resource resource) {
        getDelegateForUpdate().addResource(resource);
    }

    @Override
    public void removeResource(Resource resource) {
        getDelegateForUpdate().removeResource(resource);
    }

    // ---- lazy resolution through cached store factory ----

    private Set<Policy> resolvePolicies(Set<String> ids) {
        PolicyStore store = factory().getPolicyStore();
        Set<Policy> result = new LinkedHashSet<>();
        for (String id : ids) {
            Policy p = store.findById(resourceServer, id);
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    private Set<Resource> resolveResources(Set<String> ids) {
        ResourceStore store = factory().getResourceStore();
        Set<Resource> result = new LinkedHashSet<>();
        for (String id : ids) {
            Resource r = store.findById(resourceServer, id);
            if (r != null) {
                result.add(r);
            }
        }
        return result;
    }

    private Set<Scope> resolveScopes(Set<String> ids) {
        ScopeStore store = factory().getScopeStore();
        Set<Scope> result = new LinkedHashSet<>();
        for (String id : ids) {
            Scope s = store.findById(resourceServer, id);
            if (s != null) {
                result.add(s);
            }
        }
        return result;
    }

    private CachedStoreFactoryProvider factory() {
        return session.getProvider(CachedStoreFactoryProvider.class);
    }

    private Policy getDelegateForUpdate() {
        if (delegate == null) {
            AuthorizationInvalidation.invalidate(session, cache, cached.getResourceServerId());
            delegate = delegateStore.findById(resourceServer, cached.getId());
            if (delegate == null) {
                throw new IllegalStateException(
                        "Policy " + cached.getId() + " no longer exists in the backing store");
            }
        }
        return delegate;
    }
}
