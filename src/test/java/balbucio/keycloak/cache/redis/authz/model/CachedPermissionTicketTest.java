package balbucio.keycloak.cache.redis.authz.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.authorization.model.PermissionTicket;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

class CachedPermissionTicketTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshotCapturesAllFields() {
        ResourceServer rs = new TestResourceServer("rs-1");
        Resource resource = new TestResource("res-x");
        Scope scope = new TestScope("scope-1");
        Policy policy = new TestPolicy("p-grant");

        TestTicket ticket = new TestTicket(
                "ticket-1", "owner-1", "req-1", resource, scope, rs, true, 1000L, 2000L, policy);

        CachedPermissionTicket cached = CachedPermissionTicket.from(ticket);

        assertEquals("ticket-1", cached.getId());
        assertEquals("owner-1", cached.getOwner());
        assertEquals("req-1", cached.getRequester());
        assertEquals("res-x", cached.getResourceId());
        assertEquals("scope-1", cached.getScopeId());
        assertEquals("rs-1", cached.getResourceServerId());
        assertEquals("p-grant", cached.getPolicyId());
        assertTrue(cached.isGranted());
        assertEquals(1000L, cached.getCreatedTimestamp());
        assertEquals(2000L, cached.getGrantedTimestamp());
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        TestTicket ticket = new TestTicket(
                "t-2", "o", "r", new TestResource("rr"), null,
                new TestResourceServer("rs-7"), false, 10L, null, null);

        CachedPermissionTicket cached = CachedPermissionTicket.from(ticket);
        String json = mapper.writeValueAsString(cached);
        CachedPermissionTicket back = mapper.readValue(json, CachedPermissionTicket.class);

        assertEquals("t-2", back.getId());
        assertEquals("rr", back.getResourceId());
        assertEquals("rs-7", back.getResourceServerId());
        assertFalse(back.isGranted());
        assertNull(back.getScopeId());
        assertNull(back.getPolicyId());
        assertNull(back.getGrantedTimestamp());
    }

    // ---- test doubles ----

    private static class TestResourceServer implements ResourceServer {
        private final String id;
        TestResourceServer(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public boolean isAllowRemoteResourceManagement() { return false; }
        @Override public void setAllowRemoteResourceManagement(boolean b) {}
        @Override public org.keycloak.representations.idm.authorization.PolicyEnforcementMode getPolicyEnforcementMode() { return null; }
        @Override public void setPolicyEnforcementMode(org.keycloak.representations.idm.authorization.PolicyEnforcementMode m) {}
        @Override public void setDecisionStrategy(org.keycloak.representations.idm.authorization.DecisionStrategy d) {}
        @Override public org.keycloak.representations.idm.authorization.DecisionStrategy getDecisionStrategy() { return null; }
        @Override public String getClientId() { return null; }
    }

    private static class TestResource implements Resource {
        private final String id;
        TestResource(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public String getName() { return null; }
        @Override public void setName(String n) {}
        @Override public String getDisplayName() { return null; }
        @Override public void setDisplayName(String d) {}
        @Override public java.util.Set<String> getUris() { return null; }
        @Override public void updateUris(java.util.Set<String> u) {}
        @Override public String getType() { return null; }
        @Override public void setType(String t) {}
        @Override public java.util.List<Scope> getScopes() { return null; }
        @Override public String getIconUri() { return null; }
        @Override public void setIconUri(String i) {}
        @Override public ResourceServer getResourceServer() { return null; }
        @Override public String getOwner() { return null; }
        @Override public boolean isOwnerManagedAccess() { return false; }
        @Override public void setOwnerManagedAccess(boolean o) {}
        @Override public void updateScopes(java.util.Set<Scope> s) {}
        @Override public java.util.Map<String, java.util.List<String>> getAttributes() { return null; }
        @Override public String getSingleAttribute(String name) { return null; }
        @Override public java.util.List<String> getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, java.util.List<String> values) {}
        @Override public void removeAttribute(String name) {}
    }

    private static class TestScope implements Scope {
        private final String id;
        TestScope(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public String getName() { return null; }
        @Override public void setName(String n) {}
        @Override public String getDisplayName() { return null; }
        @Override public void setDisplayName(String d) {}
        @Override public String getIconUri() { return null; }
        @Override public void setIconUri(String i) {}
        @Override public ResourceServer getResourceServer() { return null; }
    }

    private static class TestPolicy implements Policy {
        private final String id;
        TestPolicy(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public String getType() { return null; }
        @Override public org.keycloak.representations.idm.authorization.DecisionStrategy getDecisionStrategy() { return null; }
        @Override public void setDecisionStrategy(org.keycloak.representations.idm.authorization.DecisionStrategy d) {}
        @Override public org.keycloak.representations.idm.authorization.Logic getLogic() { return null; }
        @Override public void setLogic(org.keycloak.representations.idm.authorization.Logic l) {}
        @Override public java.util.Map<String, String> getConfig() { return null; }
        @Override public void setConfig(java.util.Map<String, String> c) {}
        @Override public void removeConfig(String n) {}
        @Override public void putConfig(String n, String v) {}
        @Override public String getName() { return null; }
        @Override public void setName(String n) {}
        @Override public String getDescription() { return null; }
        @Override public void setDescription(String d) {}
        @Override public ResourceServer getResourceServer() { return null; }
        @Override public java.util.Set<Policy> getAssociatedPolicies() { return null; }
        @Override public java.util.Set<Resource> getResources() { return null; }
        @Override public java.util.Set<Scope> getScopes() { return null; }
        @Override public String getOwner() { return null; }
        @Override public void setOwner(String o) {}
        @Override public void addScope(Scope s) {}
        @Override public void removeScope(Scope s) {}
        @Override public void addAssociatedPolicy(Policy p) {}
        @Override public void removeAssociatedPolicy(Policy p) {}
        @Override public void addResource(Resource r) {}
        @Override public void removeResource(Resource r) {}
    }

    private static class TestTicket implements PermissionTicket {
        private final String id, owner, requester;
        private final Resource resource;
        private final Scope scope;
        private final ResourceServer rs;
        private final boolean isGranted;
        private final Long created;
        private final Long grantedTs;
        private final Policy policy;

        TestTicket(String id, String owner, String requester, Resource resource, Scope scope,
                   ResourceServer rs, boolean granted, Long created, Long grantedTs, Policy policy) {
            this.id = id; this.owner = owner; this.requester = requester; this.resource = resource;
            this.scope = scope; this.rs = rs; this.isGranted = granted; this.created = created;
            this.grantedTs = grantedTs; this.policy = policy;
        }
        @Override public String getId() { return id; }
        @Override public String getOwner() { return owner; }
        @Override public String getRequester() { return requester; }
        @Override public Resource getResource() { return resource; }
        @Override public Scope getScope() { return scope; }
        @Override public boolean isGranted() { return isGranted; }
        @Override public Long getCreatedTimestamp() { return created; }
        @Override public Long getGrantedTimestamp() { return grantedTs; }
        @Override public void setGrantedTimestamp(Long t) {}
        @Override public ResourceServer getResourceServer() { return rs; }
        @Override public Policy getPolicy() { return policy; }
        @Override public void setPolicy(Policy p) {}
    }
}
