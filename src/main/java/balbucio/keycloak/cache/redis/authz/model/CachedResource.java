package balbucio.keycloak.cache.redis.authz.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.Scope;

/**
 * Immutable serializable snapshot of a {@link Resource} for the Redis cache-aside layer.
 *
 * <p>Scopes are captured as a list of scope IDs (not live objects) to avoid serializing JPA
 * entities. The {@code resourceServerId} replaces the live {@link
 * org.keycloak.authorization.model.ResourceServer} reference.
 */
public class CachedResource {

    private String id;
    private String name;
    private String displayName;
    private String type;
    private String iconUri;
    private Set<String> uris;
    private String owner;
    private boolean ownerManagedAccess;
    private String resourceServerId;
    private List<String> scopeIds;
    private Map<String, List<String>> attributes;

    public CachedResource() {}

    public static CachedResource from(Resource resource) {
        CachedResource cached = new CachedResource();
        cached.id = resource.getId();
        cached.name = resource.getName();
        cached.displayName = resource.getDisplayName();
        cached.type = resource.getType();
        cached.iconUri = resource.getIconUri();
        cached.uris = resource.getUris() != null ? new TreeSet<>(resource.getUris()) : new TreeSet<>();
        cached.owner = resource.getOwner();
        cached.ownerManagedAccess = resource.isOwnerManagedAccess();

        if (resource.getResourceServer() != null) {
            cached.resourceServerId = resource.getResourceServer().getId();
        }

        if (resource.getScopes() != null) {
            cached.scopeIds = new ArrayList<>();
            for (Scope scope : resource.getScopes()) {
                if (scope != null && scope.getId() != null) {
                    cached.scopeIds.add(scope.getId());
                }
            }
        } else {
            cached.scopeIds = new ArrayList<>();
        }

        if (resource.getAttributes() != null) {
            cached.attributes = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : resource.getAttributes().entrySet()) {
                cached.attributes.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        } else {
            cached.attributes = new LinkedHashMap<>();
        }
        return cached;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getIconUri() {
        return iconUri;
    }

    public void setIconUri(String iconUri) {
        this.iconUri = iconUri;
    }

    public Set<String> getUris() {
        return uris;
    }

    public void setUris(Set<String> uris) {
        this.uris = uris;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public boolean isOwnerManagedAccess() {
        return ownerManagedAccess;
    }

    public void setOwnerManagedAccess(boolean ownerManagedAccess) {
        this.ownerManagedAccess = ownerManagedAccess;
    }

    public String getResourceServerId() {
        return resourceServerId;
    }

    public void setResourceServerId(String resourceServerId) {
        this.resourceServerId = resourceServerId;
    }

    public List<String> getScopeIds() {
        return scopeIds;
    }

    public void setScopeIds(List<String> scopeIds) {
        this.scopeIds = scopeIds;
    }

    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, List<String>> attributes) {
        this.attributes = attributes;
    }
}
