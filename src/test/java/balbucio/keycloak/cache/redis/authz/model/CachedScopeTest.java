package balbucio.keycloak.cache.redis.authz.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;

class CachedScopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshotCapturesAllFields() {
        ResourceServer rs = new TestResourceServer("rs-3");
        Scope scope = new TestScope("scope-1", "read", "Read Access", "icon://x", rs);

        CachedScope cached = CachedScope.from(scope);

        assertEquals("scope-1", cached.getId());
        assertEquals("read", cached.getName());
        assertEquals("Read Access", cached.getDisplayName());
        assertEquals("icon://x", cached.getIconUri());
        assertEquals("rs-3", cached.getResourceServerId());
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        Scope scope = new TestScope("s-2", "write", null, null, new TestResourceServer("rs-9"));
        CachedScope cached = CachedScope.from(scope);
        String json = mapper.writeValueAsString(cached);
        CachedScope back = mapper.readValue(json, CachedScope.class);

        assertEquals("s-2", back.getId());
        assertEquals("write", back.getName());
        assertEquals("rs-9", back.getResourceServerId());
        assertTrue(back.getDisplayName() == null || back.getDisplayName().isEmpty()
                || back.getDisplayName() == null);
    }

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

    private static class TestScope implements Scope {
        private final String id, name, displayName, iconUri;
        private final ResourceServer rs;
        TestScope(String id, String name, String displayName, String iconUri, ResourceServer rs) {
            this.id = id; this.name = name; this.displayName = displayName; this.iconUri = iconUri; this.rs = rs;
        }
        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public void setName(String n) {}
        @Override public String getDisplayName() { return displayName; }
        @Override public void setDisplayName(String d) {}
        @Override public String getIconUri() { return iconUri; }
        @Override public void setIconUri(String i) {}
        @Override public ResourceServer getResourceServer() { return rs; }
    }
}
