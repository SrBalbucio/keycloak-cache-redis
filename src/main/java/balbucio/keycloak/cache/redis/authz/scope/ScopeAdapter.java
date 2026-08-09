package balbucio.keycloak.cache.redis.authz.scope;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedScope;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.KeycloakSession;

/**
 * {@link Scope} backed by a {@link CachedScope} snapshot for reads and a lazily-loaded JPA
 * delegate for writes.
 */
public class ScopeAdapter implements Scope {

    private final KeycloakSession session;
    private final CachedScope cached;
    private final ResourceServer resourceServer;
    private final ScopeStore delegateStore;
    private final RedisAuthorizationCache cache;

    private Scope delegate;

    public ScopeAdapter(
            KeycloakSession session,
            CachedScope cached,
            ResourceServer resourceServer,
            ScopeStore delegateStore,
            RedisAuthorizationCache cache) {
        this.session = session;
        this.cached = cached;
        this.resourceServer = resourceServer;
        this.delegateStore = delegateStore;
        this.cache = cache;
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

    private Scope getDelegateForUpdate() {
        if (delegate == null) {
            AuthorizationInvalidation.invalidate(session, cache, cached.getResourceServerId());
            delegate = delegateStore.findById(resourceServer, cached.getId());
            if (delegate == null) {
                throw new IllegalStateException(
                        "Scope " + cached.getId() + " no longer exists in the backing store");
            }
        }
        return delegate;
    }
}
