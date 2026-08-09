package balbucio.keycloak.cache.redis.authz.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.Scope;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;

/**
 * Immutable serializable snapshot of a {@link Policy} for the Redis cache-aside layer.
 *
 * <p>Related entities (associated policies, resources, scopes) are captured as ID sets to avoid
 * serializing live JPA entities. The adapter resolves them lazily through the cached store factory.
 */
public class CachedPolicy {

    private String id;
    private String type;
    private DecisionStrategy decisionStrategy;
    private Logic logic;
    private Map<String, String> config;
    private String name;
    private String description;
    private String owner;
    private String resourceServerId;
    private Set<String> associatedPolicyIds;
    private Set<String> resourceIds;
    private Set<String> scopeIds;

    public CachedPolicy() {}

    public static CachedPolicy from(Policy policy) {
        CachedPolicy cached = new CachedPolicy();
        cached.id = policy.getId();
        cached.type = policy.getType();
        cached.decisionStrategy = policy.getDecisionStrategy();
        cached.logic = policy.getLogic();
        cached.name = policy.getName();
        cached.description = policy.getDescription();
        cached.owner = policy.getOwner();

        Map<String, String> config = policy.getConfig();
        cached.config = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();

        if (policy.getResourceServer() != null) {
            cached.resourceServerId = policy.getResourceServer().getId();
        }

        Set<Policy> associated = policy.getAssociatedPolicies();
        cached.associatedPolicyIds = new LinkedHashSet<>();
        if (associated != null) {
            for (Policy p : associated) {
                if (p != null && p.getId() != null) {
                    cached.associatedPolicyIds.add(p.getId());
                }
            }
        }

        Set<Resource> resources = policy.getResources();
        cached.resourceIds = new LinkedHashSet<>();
        if (resources != null) {
            for (Resource r : resources) {
                if (r != null && r.getId() != null) {
                    cached.resourceIds.add(r.getId());
                }
            }
        }

        Set<Scope> scopes = policy.getScopes();
        cached.scopeIds = new LinkedHashSet<>();
        if (scopes != null) {
            for (Scope s : scopes) {
                if (s != null && s.getId() != null) {
                    cached.scopeIds.add(s.getId());
                }
            }
        }
        return cached;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public DecisionStrategy getDecisionStrategy() {
        return decisionStrategy;
    }

    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        this.decisionStrategy = decisionStrategy;
    }

    public Logic getLogic() {
        return logic;
    }

    public void setLogic(Logic logic) {
        this.logic = logic;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public void setConfig(Map<String, String> config) {
        this.config = config;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getResourceServerId() {
        return resourceServerId;
    }

    public void setResourceServerId(String resourceServerId) {
        this.resourceServerId = resourceServerId;
    }

    public Set<String> getAssociatedPolicyIds() {
        return associatedPolicyIds;
    }

    public void setAssociatedPolicyIds(Set<String> associatedPolicyIds) {
        this.associatedPolicyIds = associatedPolicyIds;
    }

    public Set<String> getResourceIds() {
        return resourceIds;
    }

    public void setResourceIds(Set<String> resourceIds) {
        this.resourceIds = resourceIds;
    }

    public Set<String> getScopeIds() {
        return scopeIds;
    }

    public void setScopeIds(Set<String> scopeIds) {
        this.scopeIds = scopeIds;
    }
}
