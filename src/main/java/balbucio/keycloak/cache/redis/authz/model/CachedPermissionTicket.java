package balbucio.keycloak.cache.redis.authz.model;

import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

/**
 * Immutable serializable snapshot of a {@link PermissionTicket} for the Redis cache-aside layer.
 *
 * <p>Related entities (resource, scope, resource server, policy) are captured as IDs. The adapter
 * resolves them lazily through the cached store factory.
 */
public class CachedPermissionTicket {

    private String id;
    private String owner;
    private String requester;
    private String resourceId;
    private String scopeId;
    private boolean granted;
    private Long createdTimestamp;
    private Long grantedTimestamp;
    private String resourceServerId;
    private String policyId;

    public CachedPermissionTicket() {}

    public static CachedPermissionTicket from(PermissionTicket ticket) {
        CachedPermissionTicket cached = new CachedPermissionTicket();
        cached.id = ticket.getId();
        cached.owner = ticket.getOwner();
        cached.requester = ticket.getRequester();
        cached.granted = ticket.isGranted();
        cached.createdTimestamp = ticket.getCreatedTimestamp();
        cached.grantedTimestamp = ticket.getGrantedTimestamp();

        Resource resource = ticket.getResource();
        if (resource != null) {
            cached.resourceId = resource.getId();
        }
        Scope scope = ticket.getScope();
        if (scope != null) {
            cached.scopeId = scope.getId();
        }
        ResourceServer rs = ticket.getResourceServer();
        if (rs != null) {
            cached.resourceServerId = rs.getId();
        }
        Policy policy = ticket.getPolicy();
        if (policy != null) {
            cached.policyId = policy.getId();
        }
        return cached;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRequester() {
        return requester;
    }

    public void setRequester(String requester) {
        this.requester = requester;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    public Long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(Long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public Long getGrantedTimestamp() {
        return grantedTimestamp;
    }

    public void setGrantedTimestamp(Long grantedTimestamp) {
        this.grantedTimestamp = grantedTimestamp;
    }

    public String getResourceServerId() {
        return resourceServerId;
    }

    public void setResourceServerId(String resourceServerId) {
        this.resourceServerId = resourceServerId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }
}
