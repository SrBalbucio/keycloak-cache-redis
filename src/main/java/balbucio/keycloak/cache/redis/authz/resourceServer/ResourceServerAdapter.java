package balbucio.keycloak.cache.redis.authz.resourceServer;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedResourceServer;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode;

/**
 * {@link ResourceServer} backed by a {@link CachedResourceServer} snapshot for reads and a
 * lazily-loaded JPA delegate for writes.
 */
public class ResourceServerAdapter implements ResourceServer {

    private final KeycloakSession session;
    private final CachedResourceServer cached;
    private final ResourceServerStore delegateStore;
    private final RedisAuthorizationCache cache;

    private ResourceServer delegate;

    public ResourceServerAdapter(
            KeycloakSession session,
            CachedResourceServer cached,
            ResourceServerStore delegateStore,
            RedisAuthorizationCache cache) {
        this.session = session;
        this.cached = cached;
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
    public boolean isAllowRemoteResourceManagement() {
        if (delegate != null) {
            return delegate.isAllowRemoteResourceManagement();
        }
        return cached.isAllowRemoteResourceManagement();
    }

    @Override
    public void setAllowRemoteResourceManagement(boolean allowRemoteResourceManagement) {
        getDelegateForUpdate().setAllowRemoteResourceManagement(allowRemoteResourceManagement);
    }

    @Override
    public PolicyEnforcementMode getPolicyEnforcementMode() {
        if (delegate != null) {
            return delegate.getPolicyEnforcementMode();
        }
        return cached.getPolicyEnforcementMode();
    }

    @Override
    public void setPolicyEnforcementMode(PolicyEnforcementMode mode) {
        getDelegateForUpdate().setPolicyEnforcementMode(mode);
    }

    @Override
    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        getDelegateForUpdate().setDecisionStrategy(decisionStrategy);
    }

    @Override
    public DecisionStrategy getDecisionStrategy() {
        if (delegate != null) {
            return delegate.getDecisionStrategy();
        }
        return cached.getDecisionStrategy();
    }

    @Override
    public String getClientId() {
        if (delegate != null) {
            return delegate.getClientId();
        }
        return cached.getClientId();
    }

    private ResourceServer getDelegateForUpdate() {
        if (delegate == null) {
            AuthorizationInvalidation.invalidate(session, cache, cached.getId());
            delegate = delegateStore.findById(cached.getId());
            if (delegate == null) {
                throw new IllegalStateException(
                        "ResourceServer " + cached.getId() + " no longer exists in the backing store");
            }
        }
        return delegate;
    }
}
