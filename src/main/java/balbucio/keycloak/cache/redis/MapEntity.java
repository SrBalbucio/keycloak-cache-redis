package balbucio.keycloak.cache.redis;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.ExpirableEntity;
import balbucio.keycloak.cache.redis.common.TimeAdapter;

/**
 * Hash-backed entity with dirty/deleted tracking for CAS commits.
 */
public class MapEntity implements ExpirableEntity {

    private final Map<String, String> data = new HashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private boolean markedForDelete;
    private boolean created;
    private Long loadedVersion;

    public MapEntity() {}

    public static MapEntity fromRedis(Map<String, String> hash) {
        MapEntity entity = new MapEntity();
        if (hash != null) {
            entity.data.putAll(hash);
            entity.loadedVersion = TimeAdapter.parseLong(hash.get(Constants.VERSION_FIELD));
        }
        return entity;
    }

    public static MapEntity createNew() {
        MapEntity entity = new MapEntity();
        entity.created = true;
        entity.loadedVersion = null;
        entity.set(Constants.VERSION_FIELD, "0");
        return entity;
    }

    public boolean isCreated() {
        return created;
    }

    public boolean isMarkedForDelete() {
        return markedForDelete;
    }

    public void markForDelete() {
        markedForDelete = true;
        dirty.clear();
        deleted.clear();
    }

    public Long getLoadedVersion() {
        return loadedVersion;
    }

    public void setLoadedVersion(Long loadedVersion) {
        this.loadedVersion = loadedVersion;
    }

    public boolean hasPendingChanges() {
        return markedForDelete || created || !dirty.isEmpty() || !deleted.isEmpty();
    }

    public String get(String field) {
        if (deleted.contains(field)) {
            return null;
        }
        String value = data.get(field);
        if (Constants.NULL_SENTINEL.equals(value)) {
            return null;
        }
        return value;
    }

    public void set(String field, String value) {
        if (markedForDelete) {
            return;
        }
        deleted.remove(field);
        if (value == null) {
            data.put(field, Constants.NULL_SENTINEL);
        } else {
            data.put(field, value);
        }
        dirty.add(field);
    }

    public void remove(String field) {
        if (markedForDelete) {
            return;
        }
        data.remove(field);
        dirty.remove(field);
        deleted.add(field);
    }

    public Map<String, String> getMap(String prefix) {
        String p = prefix.endsWith(".") ? prefix : prefix + ".";
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (!e.getKey().startsWith(p) || deleted.contains(e.getKey())) {
                continue;
            }
            String value = e.getValue();
            if (Constants.NULL_SENTINEL.equals(value)) {
                continue;
            }
            result.put(e.getKey().substring(p.length()), value);
        }
        return Collections.unmodifiableMap(result);
    }

    public void putMapEntry(String prefix, String key, String value) {
        set(normalizePrefix(prefix) + key, value);
    }

    public void removeMapEntry(String prefix, String key) {
        remove(normalizePrefix(prefix) + key);
    }

    public Set<String> getSet(String prefix) {
        return new LinkedHashSet<>(getMap(prefix).keySet());
    }

    public void addSetEntry(String prefix, String value) {
        putMapEntry(prefix, value, "1");
    }

    public void removeSetEntry(String prefix, String value) {
        removeMapEntry(prefix, value);
    }

    public Map<String, String> pendingSets() {
        Map<String, String> sets = new LinkedHashMap<>();
        for (String field : dirty) {
            sets.put(field, data.get(field));
        }
        return sets;
    }

    public Set<String> pendingDeletes() {
        return Collections.unmodifiableSet(new HashSet<>(deleted));
    }

    public Map<String, String> snapshot() {
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (!deleted.contains(e.getKey())) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        return copy;
    }

    /**
     * Field-level rebase: replay pending dirty/deleted changes onto freshly loaded data.
     */
    public void rebase(Map<String, String> fresh) {
        Map<String, String> pending = pendingSets();
        Set<String> pendingDel = new HashSet<>(deleted);
        boolean wasCreated = created;
        boolean wasDelete = markedForDelete;

        data.clear();
        dirty.clear();
        deleted.clear();
        created = false;
        markedForDelete = false;

        if (fresh != null) {
            data.putAll(fresh);
            loadedVersion = TimeAdapter.parseLong(fresh.get(Constants.VERSION_FIELD));
        } else {
            loadedVersion = null;
            created = wasCreated;
        }

        if (wasDelete) {
            markedForDelete = true;
            return;
        }

        for (Map.Entry<String, String> e : pending.entrySet()) {
            data.put(e.getKey(), e.getValue());
            dirty.add(e.getKey());
        }
        for (String field : pendingDel) {
            data.remove(field);
            dirty.remove(field);
            deleted.add(field);
        }
    }

    public void clearChangeTracking(Long newVersion) {
        dirty.clear();
        deleted.clear();
        created = false;
        markedForDelete = false;
        loadedVersion = newVersion;
        if (newVersion != null) {
            data.put(Constants.VERSION_FIELD, Long.toString(newVersion));
        }
    }

    /**
     * Copies data and change-tracking state from another entity into this instance.
     */
    public void copyFrom(MapEntity other) {
        data.clear();
        dirty.clear();
        deleted.clear();
        data.putAll(other.data);
        dirty.addAll(other.dirty);
        deleted.addAll(other.deleted);
        markedForDelete = other.markedForDelete;
        created = other.created;
        loadedVersion = other.loadedVersion;
    }

    @Override
    public Long getExpiration() {
        return TimeAdapter.parseLong(get(Constants.EXPIRATION_FIELD));
    }

    @Override
    public void setExpiration(Long expiration) {
        if (expiration == null) {
            remove(Constants.EXPIRATION_FIELD);
        } else {
            set(Constants.EXPIRATION_FIELD, Long.toString(expiration));
        }
    }

    private static String normalizePrefix(String prefix) {
        return prefix.endsWith(".") ? prefix : prefix + ".";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MapEntity that)) {
            return false;
        }
        return Objects.equals(snapshot(), that.snapshot());
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshot());
    }
}
