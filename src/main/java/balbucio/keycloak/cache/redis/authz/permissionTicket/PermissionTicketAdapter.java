package balbucio.keycloak.cache.redis.authz.permissionTicket;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedPermissionTicket;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.cache.authorization.CachedStoreFactoryProvider;

/**
 * {@link PermissionTicket} backed by a {@link CachedPermissionTicket} snapshot for reads and a
 * lazily-loaded JPA delegate for writes. Related entities are resolved lazily through the cached
 * store factory.
 */
public class PermissionTicketAdapter implements PermissionTicket {

    private final KeycloakSession session;
    private final CachedPermissionTicket cached;
    private final ResourceServer resourceServer;
    private final PermissionTicketStore delegateStore;
    private final RedisAuthorizationCache cache;

    private PermissionTicket delegate;

    public PermissionTicketAdapter(
            KeycloakSession session,
            CachedPermissionTicket cached,
            ResourceServer resourceServer,
            PermissionTicketStore delegateStore,
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
    public String getOwner() {
        if (delegate != null) {
            return delegate.getOwner();
        }
        return cached.getOwner();
    }

    @Override
    public String getRequester() {
        if (delegate != null) {
            return delegate.getRequester();
        }
        return cached.getRequester();
    }

    @Override
    public Resource getResource() {
        if (delegate != null) {
            return delegate.getResource();
        }
        if (cached.getResourceId() == null) {
            return null;
        }
        return factory().getResourceStore().findById(resourceServer, cached.getResourceId());
    }

    @Override
    public Scope getScope() {
        if (delegate != null) {
            return delegate.getScope();
        }
        if (cached.getScopeId() == null) {
            return null;
        }
        return factory().getScopeStore().findById(resourceServer, cached.getScopeId());
    }

    @Override
    public boolean isGranted() {
        if (delegate != null) {
            return delegate.isGranted();
        }
        return cached.isGranted();
    }

    @Override
    public Long getCreatedTimestamp() {
        if (delegate != null) {
            return delegate.getCreatedTimestamp();
        }
        return cached.getCreatedTimestamp();
    }

    @Override
    public Long getGrantedTimestamp() {
        if (delegate != null) {
            return delegate.getGrantedTimestamp();
        }
        return cached.getGrantedTimestamp();
    }

    @Override
    public void setGrantedTimestamp(Long grantedTimestamp) {
        getDelegateForUpdate().setGrantedTimestamp(grantedTimestamp);
    }

    @Override
    public ResourceServer getResourceServer() {
        if (delegate != null) {
            return delegate.getResourceServer();
        }
        return resourceServer;
    }

    @Override
    public Policy getPolicy() {
        if (delegate != null) {
            return delegate.getPolicy();
        }
        if (cached.getPolicyId() == null) {
            return null;
        }
        return factory().getPolicyStore().findById(resourceServer, cached.getPolicyId());
    }

    @Override
    public void setPolicy(Policy policy) {
        getDelegateForUpdate().setPolicy(policy);
    }

    private CachedStoreFactoryProvider factory() {
        return session.getProvider(CachedStoreFactoryProvider.class);
    }

    private PermissionTicket getDelegateForUpdate() {
        if (delegate == null) {
            AuthorizationInvalidation.invalidate(session, cache, cached.getResourceServerId());
            delegate = delegateStore.findById(resourceServer, cached.getId());
            if (delegate == null) {
                throw new IllegalStateException(
                        "PermissionTicket " + cached.getId() + " no longer exists in the backing store");
            }
        }
        return delegate;
    }
}
