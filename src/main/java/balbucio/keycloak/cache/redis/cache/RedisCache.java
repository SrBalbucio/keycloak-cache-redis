package balbucio.keycloak.cache.redis.cache;

import java.util.concurrent.Callable;

/**
 * Namespace-bound Redis cache for Keycloak extensions.
 *
 * <p>Semantics (v1):
 * <ul>
 *   <li>Keys live under {@code <prefix>cache-api:&lt;namespace&gt;:&lt;key&gt;} — namespaces never
 *       leak into each other (data, LRU and invalidation channels alike).
 *   <li>{@code String} values are stored raw; any other type as JSON. Reads are type-guarded:
 *       a type mismatch is a miss, never a {@code ClassCastException}.
 *   <li>{@link #put} and {@link #remove} broadcast L1 invalidation to other nodes, so a
 *       node-local LRU never goes stale beyond the broadcast.
 *   <li>{@link #clear()} clears the local LRU everywhere via broadcast; Redis (L2) entries
 *       keep their own TTL (there is no namespace enumeration by design).
 *   <li>Fail-open: Redis failures degrade to miss / no-op and never throw (mirrors the
 *       authorization cache). Loader exceptions from {@link #getOrLoad} propagate — that is
 *       caller logic, not cache failure.
 * </ul>
 */
public interface RedisCache {

    /** Namespace this view is bound to. */
    String namespace();

    /**
     * Reads {@code key} as {@code type}. Returns {@code null} on absent, expired, expired-local,
     * type-mismatched or (fail-open) unreadable entries.
     */
    <T> T get(String key, Class<T> type);

    /**
     * Cache-aside read: on miss, runs {@code loader}, caches a non-null result with
     * {@code ttlSeconds} and returns it. A {@code null} loader result is returned without
     * caching. Loader exceptions propagate to the caller.
     */
    <T> T getOrLoad(String key, Class<T> type, long ttlSeconds, Callable<T> loader) throws Exception;

    /**
     * Stores {@code value} with {@code ttlSeconds} TTL ({@code <= 0} means persistent in
     * Redis; the node-local LRU entry is still bounded by config). A {@code null} value
     * behaves like {@link #remove(String)}. Broadcasts L1 invalidation to other nodes.
     */
    <T> void put(String key, T value, long ttlSeconds);

    /** Deletes {@code key} everywhere known (L2 + local L1 + broadcast to other nodes' L1). */
    void remove(String key);

    /**
     * Clears the node-local LRU on every node via broadcast. Redis (L2) entries are NOT
     * enumerated — they expire by their own TTL. Prefer {@link #remove(String)} for precise,
     * immediate invalidation.
     */
    void clear();
}
