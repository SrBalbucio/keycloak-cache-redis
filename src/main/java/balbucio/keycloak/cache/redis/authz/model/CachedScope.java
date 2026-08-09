package balbucio.keycloak.cache.redis.authz.model;

import org.keycloak.authorization.model.Scope;

/**
 * Immutable serializable snapshot of a {@link Scope} for the Redis cache-aside layer.
 */
public class CachedScope {

    private String id;
    private String name;
    private String displayName;
    private String iconUri;
    private String resourceServerId;

    public CachedScope() {}

    public static CachedScope from(Scope scope) {
        CachedScope cached = new CachedScope();
        cached.id = scope.getId();
        cached.name = scope.getName();
        cached.displayName = scope.getDisplayName();
        cached.iconUri = scope.getIconUri();
        if (scope.getResourceServer() != null) {
            cached.resourceServerId = scope.getResourceServer().getId();
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

    public String getIconUri() {
        return iconUri;
    }

    public void setIconUri(String iconUri) {
        this.iconUri = iconUri;
    }

    public String getResourceServerId() {
        return resourceServerId;
    }

    public void setResourceServerId(String resourceServerId) {
        this.resourceServerId = resourceServerId;
    }
}
