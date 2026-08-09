package balbucio.keycloak.cache.redis.authz.resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedResource;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.models.KeycloakSession;

/**
 * {@link Resource} backed by a {@link CachedResource} snapshot for reads and a lazily-loaded JPA
 * delegate for writes. On the first write, the cache is invalidated for the resource server and the
 * real JPA model is loaded.
 */
public class ResourceAdapter implements Resource {

    private final KeycloakSession session;
    private final CachedResource cached;
    private final ResourceServer resourceServer;
    private final ResourceStore delegateStore;
    private final RedisAuthorizationCache cache;

    private Resource delegate;

    public ResourceAdapter(
            KeycloakSession session,
            CachedResource cached,
            ResourceServer resourceServer,
            ResourceStore delegateStore,
            RedisAuthorizationCache cache) {
        this.session = session;
        this.cached = cached;
        this.resourceServer = resourceServer;
        this.delegateStore = delegateStore;
        this.cache = cache;
    }

    public CachedResource getCached() {
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
    public String getDisplayName() {
        if (delegate != null) {
            return delegate.getDisplayName();
        }
        return cached.getDisplayName();
    }

    @Override
    public void setDisplayName(String displayName) {
        getDelegateForUpdate().setDisplayName(displayName);
    }

    @Override
    public Set<String> getUris() {
        if (delegate != null) {
            return delegate.getUris();
        }
        Set<String> uris = cached.getUris();
        return uris != null ? new TreeSet<>(uris) : Collections.emptySet();
    }

    @Override
    public void updateUris(Set<String> uris) {
        getDelegateForUpdate().updateUris(uris);
    }

    @Override
    public String getType() {
        if (delegate != null) {
            return delegate.getType();
        }
        return cached.getType();
    }

    @Override
    public void setType(String type) {
        getDelegateForUpdate().setType(type);
    }

    @Override
    public List<Scope> getScopes() {
        if (delegate != null) {
            return delegate.getScopes();
        }
        return Collections.emptyList();
    }

    @Override
    public String getIconUri() {
        if (delegate != null) {
            return delegate.getIconUri();
        }
        return cached.getIconUri();
    }

    @Override
    public void setIconUri(String iconUri) {
        getDelegateForUpdate().setIconUri(iconUri);
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
    public boolean isOwnerManagedAccess() {
        if (delegate != null) {
            return delegate.isOwnerManagedAccess();
        }
        return cached.isOwnerManagedAccess();
    }

    @Override
    public void setOwnerManagedAccess(boolean ownerManagedAccess) {
        getDelegateForUpdate().setOwnerManagedAccess(ownerManagedAccess);
    }

    @Override
    public void updateScopes(Set<Scope> scopes) {
        getDelegateForUpdate().updateScopes(scopes);
    }

    @Override
    public Map<String, List<String>> getAttributes() {
        if (delegate != null) {
            return delegate.getAttributes();
        }
        Map<String, List<String>> attrs = cached.getAttributes();
        if (attrs == null || attrs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : attrs.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    @Override
    public String getSingleAttribute(String name) {
        if (delegate != null) {
            return delegate.getSingleAttribute(name);
        }
        List<String> values = cached.getAttributes() != null ? cached.getAttributes().get(name) : null;
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    @Override
    public List<String> getAttribute(String name) {
        if (delegate != null) {
            return delegate.getAttribute(name);
        }
        List<String> values = cached.getAttributes() != null ? cached.getAttributes().get(name) : null;
        return values != null ? new ArrayList<>(values) : Collections.emptyList();
    }

    @Override
    public void setAttribute(String name, List<String> values) {
        getDelegateForUpdate().setAttribute(name, values);
    }

    @Override
    public void removeAttribute(String name) {
        getDelegateForUpdate().removeAttribute(name);
    }

    /**
     * Loads the JPA delegate on first write and invalidates the cache. Subsequent reads also go
     * through the delegate to reflect in-flight mutations within the same transaction.
     */
    private Resource getDelegateForUpdate() {
        if (delegate == null) {
            AuthorizationInvalidation.invalidate(session, cache, cached.getResourceServerId());
            delegate = delegateStore.findById(resourceServer, cached.getId());
            if (delegate == null) {
                throw new IllegalStateException(
                        "Resource " + cached.getId() + " no longer exists in the backing store");
            }
        }
        return delegate;
    }
}
