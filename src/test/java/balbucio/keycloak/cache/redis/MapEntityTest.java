package balbucio.keycloak.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import balbucio.keycloak.cache.redis.common.Constants;
import org.junit.jupiter.api.Test;

class MapEntityTest {

    @Test
    void tracksDirtyAndDeletedFields() {
        MapEntity entity = MapEntity.createNew();
        entity.set("foo", "bar");
        entity.set("n.a", "1");
        entity.remove("foo");

        assertNull(entity.get("foo"));
        assertTrue(entity.pendingDeletes().contains("foo"));
        assertEquals("1", entity.get("n.a"));
        assertTrue(entity.pendingSets().containsKey("n.a"));
        assertTrue(entity.isCreated());
    }

    @Test
    void getMapAndGetSet() {
        MapEntity entity = MapEntity.createNew();
        entity.putMapEntry(Constants.NOTE_PREFIX, "k1", "v1");
        entity.putMapEntry(Constants.NOTE_PREFIX, "k2", "v2");
        entity.addSetEntry(Constants.SET_PREFIX, "s1");

        Map<String, String> notes = entity.getMap(Constants.NOTE_PREFIX);
        assertEquals(Map.of("k1", "v1", "k2", "v2"), notes);

        Set<String> set = entity.getSet(Constants.SET_PREFIX);
        assertEquals(Set.of("s1"), set);

        entity.removeMapEntry(Constants.NOTE_PREFIX, "k1");
        assertFalse(entity.getMap(Constants.NOTE_PREFIX).containsKey("k1"));
    }

    @Test
    void nullSentinelRoundTrip() {
        MapEntity entity = MapEntity.createNew();
        entity.set("x", null);
        assertNull(entity.get("x"));
        assertEquals(Constants.NULL_SENTINEL, entity.pendingSets().get("x"));
    }

    @Test
    void rebaseReplaysPendingChanges() {
        MapEntity entity = MapEntity.createNew();
        entity.set("a", "1");
        entity.set("b", "2");
        entity.remove("b");

        entity.rebase(Map.of(Constants.VERSION_FIELD, "3", "a", "old", "c", "keep"));

        assertEquals("1", entity.get("a"));
        assertNull(entity.get("b"));
        assertEquals("keep", entity.get("c"));
        assertEquals(3L, entity.getLoadedVersion());
        assertTrue(entity.pendingSets().containsKey("a"));
        assertTrue(entity.pendingDeletes().contains("b"));
    }

    @Test
    void markForDeleteClearsPendingWrites() {
        MapEntity entity = MapEntity.createNew();
        entity.set("a", "1");
        entity.markForDelete();
        assertTrue(entity.isMarkedForDelete());
        assertTrue(entity.pendingSets().isEmpty());
        assertTrue(entity.pendingDeletes().isEmpty());
    }

    @Test
    void fromRedisLoadsVersion() {
        MapEntity entity =
                MapEntity.fromRedis(Map.of(Constants.VERSION_FIELD, "9", "name", "alice"));
        assertEquals(9L, entity.getLoadedVersion());
        assertEquals("alice", entity.get("name"));
        assertFalse(entity.isCreated());
    }
}
