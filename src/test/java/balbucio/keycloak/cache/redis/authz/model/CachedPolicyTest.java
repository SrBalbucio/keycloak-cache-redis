package balbucio.keycloak.cache.redis.authz.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.Logic;

class CachedPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshotCapturesFieldsAndRelationIds() {
        ResourceServer rs = new TestResourceServer("rs-1");
        Set<Resource> resources = Set.of(new TestResource("res-a"), new TestResource("res-b"));
        Set<Scope> scopes = Set.of(new TestScope("scope-1"));
        Set<Policy> associated = Set.of(new TestPolicy("p-dep1"), new TestPolicy("p-dep2"));

        Policy policy = new TestPolicy(
                "p-1", "role", DecisionStrategy.UNANIMOUS, Logic.POSITIVE,
                Map.of("roles", "[\"admin\"]"), "Admin Only", "desc", "owner-1", rs,
                resources, scopes, associated);

        CachedPolicy cached = CachedPolicy.from(policy);

        assertEquals("p-1", cached.getId());
        assertEquals("role", cached.getType());
        assertEquals(DecisionStrategy.UNANIMOUS, cached.getDecisionStrategy());
        assertEquals(Logic.POSITIVE, cached.getLogic());
        assertEquals("Admin Only", cached.getName());
        assertEquals("desc", cached.getDescription());
        assertEquals("owner-1", cached.getOwner());
        assertEquals("rs-1", cached.getResourceServerId());
        assertEquals("[\"admin\"]", cached.getConfig().get("roles"));
        assertTrue(cached.getResourceIds().contains("res-a"));
        assertTrue(cached.getResourceIds().contains("res-b"));
        assertTrue(cached.getScopeIds().contains("scope-1"));
        assertTrue(cached.getAssociatedPolicyIds().contains("p-dep1"));
        assertTrue(cached.getAssociatedPolicyIds().contains("p-dep2"));
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        Policy policy = new TestPolicy(
                "p-2", "js", DecisionStrategy.AFFIRMATIVE, Logic.NEGATIVE,
                new LinkedHashMap<>(), "JS Policy", null, null,
                new TestResourceServer("rs-5"),
                Set.of(new TestResource("r-1")),
                Set.of(new TestScope("s-1")),
                Set.of());

        CachedPolicy cached = CachedPolicy.from(policy);
        String json = mapper.writeValueAsString(cached);
        CachedPolicy back = mapper.readValue(json, CachedPolicy.class);

        assertEquals("p-2", back.getId());
        assertEquals("js", back.getType());
        assertEquals(DecisionStrategy.AFFIRMATIVE, back.getDecisionStrategy());
        assertEquals(Logic.NEGATIVE, back.getLogic());
        assertEquals("rs-5", back.getResourceServerId());
        assertTrue(back.getResourceIds().contains("r-1"));
        assertTrue(back.getScopeIds().contains("s-1"));
        assertTrue(back.getAssociatedPolicyIds().isEmpty());
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
        @Override public Map<String, List<String>> getAttributes() { return null; }
        @Override public String getSingleAttribute(String name) { return null; }
        @Override public List<String> getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, List<String> values) {}
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
        private final String id, type, name, description, owner;
        private final DecisionStrategy decisionStrategy;
        private final Logic logic;
        private final Map<String, String> config;
        private final ResourceServer rs;
        private final Set<Resource> resources;
        private final Set<Scope> scopes;
        private final Set<Policy> associated;

        TestPolicy(String id, String type, DecisionStrategy ds, Logic logic,
                   Map<String, String> config, String name, String desc, String owner,
                   ResourceServer rs, Set<Resource> resources, Set<Scope> scopes, Set<Policy> associated) {
            this.id = id; this.type = type; this.decisionStrategy = ds; this.logic = logic;
            this.config = config; this.name = name; this.description = desc; this.owner = owner;
            this.rs = rs; this.resources = resources; this.scopes = scopes; this.associated = associated;
        }
        TestPolicy(String id) {
            this(id, null, null, null, new LinkedHashMap<>(), null, null, null, null,
                    new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        }
        @Override public String getId() { return id; }
        @Override public String getType() { return type; }
        @Override public DecisionStrategy getDecisionStrategy() { return decisionStrategy; }
        @Override public void setDecisionStrategy(DecisionStrategy d) {}
        @Override public Logic getLogic() { return logic; }
        @Override public void setLogic(Logic l) {}
        @Override public Map<String, String> getConfig() { return config; }
        @Override public void setConfig(Map<String, String> c) {}
        @Override public void removeConfig(String n) {}
        @Override public void putConfig(String n, String v) {}
        @Override public String getName() { return name; }
        @Override public void setName(String n) {}
        @Override public String getDescription() { return description; }
        @Override public void setDescription(String d) {}
        @Override public ResourceServer getResourceServer() { return rs; }
        @Override public Set<Policy> getAssociatedPolicies() { return associated; }
        @Override public Set<Resource> getResources() { return resources; }
        @Override public Set<Scope> getScopes() { return scopes; }
        @Override public String getOwner() { return owner; }
        @Override public void setOwner(String o) {}
        @Override public void addScope(Scope s) {}
        @Override public void removeScope(Scope s) {}
        @Override public void addAssociatedPolicy(Policy p) {}
        @Override public void removeAssociatedPolicy(Policy p) {}
        @Override public void addResource(Resource r) {}
        @Override public void removeResource(Resource r) {}
    }
}
