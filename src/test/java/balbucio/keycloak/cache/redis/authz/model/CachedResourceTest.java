package balbucio.keycloak.cache.redis.authz.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

class CachedResourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshotCapturesAllFields() {
        ResourceServer rs = new TestResourceServer("rs-1", "client-42");
        Resource resource = new TestResource("res-99", "photo", "My Photos", "urn:type", "file://icon",
                new TreeSet<>(Set.of("/photos", "/albums")), "owner-1", true, rs,
                List.of(new TestScope("scope-a"), new TestScope("scope-b")),
                Map.of("color", List.of("blue", "green")));

        CachedResource cached = CachedResource.from(resource);

        assertEquals("res-99", cached.getId());
        assertEquals("photo", cached.getName());
        assertEquals("My Photos", cached.getDisplayName());
        assertEquals("urn:type", cached.getType());
        assertEquals("file://icon", cached.getIconUri());
        assertEquals(new TreeSet<>(Set.of("/photos", "/albums")), cached.getUris());
        assertEquals("owner-1", cached.getOwner());
        assertTrue(cached.isOwnerManagedAccess());
        assertEquals("rs-1", cached.getResourceServerId());
        assertEquals(List.of("scope-a", "scope-b"), cached.getScopeIds());
        assertEquals(List.of("blue", "green"), cached.getAttributes().get("color"));
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        ResourceServer rs = new TestResourceServer("rs-7", "client-x");
        Resource resource = new TestResource("res-1", "name", null, "type", null,
                new TreeSet<>(Set.of("/uri")), "owner", false, rs,
                List.of(new TestScope("s1")), Map.of("k", List.of("v1")));

        CachedResource cached = CachedResource.from(resource);
        String json = mapper.writeValueAsString(cached);
        CachedResource back = mapper.readValue(json, CachedResource.class);

        assertEquals("res-1", back.getId());
        assertEquals("name", back.getName());
        assertEquals("type", back.getType());
        assertEquals(Set.of("/uri"), back.getUris());
        assertEquals("rs-7", back.getResourceServerId());
        assertEquals(List.of("s1"), back.getScopeIds());
        assertEquals(List.of("v1"), back.getAttributes().get("k"));
    }

    @Test
    void handlesNullResourceServer() {
        Resource resource = new TestResource("res-2", "name2", null, null, null,
                new TreeSet<>(), null, false, null, List.of(), Map.of());

        CachedResource cached = CachedResource.from(resource);
        assertNull(cached.getResourceServerId());
        assertNotNull(cached.getScopeIds());
        assertTrue(cached.getScopeIds().isEmpty());
    }

    // ---- simple test doubles ----

    private static class TestResource implements Resource {
        private final String id, name, displayName, type, iconUri;
        private final Set<String> uris;
        private final String owner;
        private final boolean managed;
        private final ResourceServer rs;
        private final List<Scope> scopes;
        private final Map<String, List<String>> attrs;

        TestResource(String id, String name, String displayName, String type, String iconUri,
                     Set<String> uris, String owner, boolean managed, ResourceServer rs,
                     List<Scope> scopes, Map<String, List<String>> attrs) {
            this.id = id; this.name = name; this.displayName = displayName; this.type = type;
            this.iconUri = iconUri; this.uris = uris; this.owner = owner; this.managed = managed;
            this.rs = rs; this.scopes = scopes; this.attrs = attrs;
        }
        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public void setName(String n) {}
        @Override public String getDisplayName() { return displayName; }
        @Override public void setDisplayName(String d) {}
        @Override public Set<String> getUris() { return uris; }
        @Override public void updateUris(Set<String> u) {}
        @Override public String getType() { return type; }
        @Override public void setType(String t) {}
        @Override public List<Scope> getScopes() { return scopes; }
        @Override public String getIconUri() { return iconUri; }
        @Override public void setIconUri(String i) {}
        @Override public ResourceServer getResourceServer() { return rs; }
        @Override public String getOwner() { return owner; }
        @Override public boolean isOwnerManagedAccess() { return managed; }
        @Override public void setOwnerManagedAccess(boolean o) {}
        @Override public void updateScopes(Set<Scope> s) {}
        @Override public Map<String, List<String>> getAttributes() { return attrs; }
        @Override public String getSingleAttribute(String name) { return null; }
        @Override public List<String> getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, List<String> values) {}
        @Override public void removeAttribute(String name) {}
    }

    private static class TestResourceServer implements ResourceServer {
        private final String id, clientId;
        TestResourceServer(String id, String clientId) { this.id = id; this.clientId = clientId; }
        @Override public String getId() { return id; }
        @Override public boolean isAllowRemoteResourceManagement() { return false; }
        @Override public void setAllowRemoteResourceManagement(boolean b) {}
        @Override public org.keycloak.representations.idm.authorization.PolicyEnforcementMode getPolicyEnforcementMode() { return null; }
        @Override public void setPolicyEnforcementMode(org.keycloak.representations.idm.authorization.PolicyEnforcementMode m) {}
        @Override public void setDecisionStrategy(org.keycloak.representations.idm.authorization.DecisionStrategy d) {}
        @Override public org.keycloak.representations.idm.authorization.DecisionStrategy getDecisionStrategy() { return null; }
        @Override public String getClientId() { return clientId; }
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
}
