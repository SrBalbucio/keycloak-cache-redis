package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.singleUseObject.RedisSingleUseObjectProvider;
import balbucio.keycloak.cache.redis.singleUseObject.SingleUseObjectKey;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.SingleUseObjectProvider;

class RedisSingleUseObjectProviderIntegrationTest extends AbstractRedisIntegrationTest {

    private RedisSingleUseObjectProvider singleUse() {
        KeycloakSession session = TestSessions.newSession(provider());
        return new RedisSingleUseObjectProvider(session);
    }

    @Test
    void putGetRemoveRoundTripWithNotes() {
        RedisSingleUseObjectProvider provider = singleUse();

        Map<String, String> notes = new HashMap<>();
        notes.put("k1", "v1");
        notes.put("null-value", null);
        provider.put("obj-1", 60, notes);

        Map<String, String> loaded = provider.get("obj-1");
        assertNotNull(loaded);
        assertEquals("v1", loaded.get("k1"));
        assertNull(loaded.get("null-value"));

        Map<String, String> removed = provider.remove("obj-1");
        assertNotNull(removed);
        assertEquals("v1", removed.get("k1"));
        assertNull(provider.get("obj-1"));
        assertFalse(provider.contains("obj-1"));
        assertNull(provider.remove("obj-1"));
    }

    @Test
    void putIfAbsentIsAtomic() {
        RedisSingleUseObjectProvider provider = singleUse();

        assertTrue(provider.putIfAbsent("obj-2", 60));
        assertFalse(provider.putIfAbsent("obj-2", 60));
        assertTrue(provider.contains("obj-2"));
    }

    @Test
    void replaceOnlyWhenKeyExists() {
        RedisSingleUseObjectProvider provider = singleUse();
        provider.put("obj-3", 60, Map.of("k", "v"));

        assertTrue(provider.replace("obj-3", Map.of("k2", "v2")));
        assertEquals("v2", provider.get("obj-3").get("k2"));
        assertFalse(provider.replace("missing", Map.of()));
    }

    @Test
    void expiredObjectIsGone() throws Exception {
        RedisSingleUseObjectProvider provider = singleUse();
        provider.put("obj-4", 1, Map.of("k", "v"));

        assertTrue(provider.contains("obj-4"));
        Thread.sleep(1200);
        assertFalse(provider.contains("obj-4"));
        assertNull(provider.get("obj-4"));
        assertEquals(0L, provider().sync().exists(SingleUseObjectKey.of("obj-4").key()));
    }

    @Test
    void revokedKeyRestrictions() {
        RedisSingleUseObjectProvider provider = singleUse();

        assertThrows(ModelException.class, () -> provider.put("tok" + SingleUseObjectProvider.REVOKED_KEY, 60, Map.of("k", "v")));
        provider.put("tok" + SingleUseObjectProvider.REVOKED_KEY, 60, null);
        assertTrue(provider.contains("tok" + SingleUseObjectProvider.REVOKED_KEY));
        assertThrows(ModelException.class, () -> provider.get("tok" + SingleUseObjectProvider.REVOKED_KEY));
        assertThrows(ModelException.class, () -> provider.remove("tok" + SingleUseObjectProvider.REVOKED_KEY));
    }

    @Test
    void rejectsNonPositiveLifespan() {
        RedisSingleUseObjectProvider provider = singleUse();
        assertThrows(IllegalArgumentException.class, () -> provider.put("obj-5", 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> provider.putIfAbsent("obj-5", -1));
    }

    @Test
    void storesInRedisWithVersionField() {
        RedisSingleUseObjectProvider provider = singleUse();
        provider.put("obj-6", 60, Map.of("k", "v"));

        RedisConnectionProvider conn = provider();
        assertEquals(1L, conn.sync().exists(SingleUseObjectKey.of("obj-6").key()));
        Map<String, String> hash = conn.sync().hgetall(SingleUseObjectKey.of("obj-6").key());
        assertEquals("v", hash.get("n.k"));
    }
}
