package balbucio.keycloak.cache.redis.authz.model;

import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.PolicyEnforcementMode;

/**
 * Immutable serializable snapshot of a {@link ResourceServer} for the Redis cache-aside layer.
 */
public class CachedResourceServer {

    private String id;
    private boolean allowRemoteResourceManagement;
    private PolicyEnforcementMode policyEnforcementMode;
    private DecisionStrategy decisionStrategy;
    private String clientId;

    public CachedResourceServer() {}

    public static CachedResourceServer from(ResourceServer resourceServer) {
        CachedResourceServer cached = new CachedResourceServer();
        cached.id = resourceServer.getId();
        cached.allowRemoteResourceManagement = resourceServer.isAllowRemoteResourceManagement();
        cached.policyEnforcementMode = resourceServer.getPolicyEnforcementMode();
        cached.decisionStrategy = resourceServer.getDecisionStrategy();
        cached.clientId = resourceServer.getClientId();
        return cached;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isAllowRemoteResourceManagement() {
        return allowRemoteResourceManagement;
    }

    public void setAllowRemoteResourceManagement(boolean allowRemoteResourceManagement) {
        this.allowRemoteResourceManagement = allowRemoteResourceManagement;
    }

    public PolicyEnforcementMode getPolicyEnforcementMode() {
        return policyEnforcementMode;
    }

    public void setPolicyEnforcementMode(PolicyEnforcementMode policyEnforcementMode) {
        this.policyEnforcementMode = policyEnforcementMode;
    }

    public DecisionStrategy getDecisionStrategy() {
        return decisionStrategy;
    }

    public void setDecisionStrategy(DecisionStrategy decisionStrategy) {
        this.decisionStrategy = decisionStrategy;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
