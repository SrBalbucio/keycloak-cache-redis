package balbucio.keycloak.cache.redis.authz.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.authz.AuthorizationCacheConfig;
import balbucio.keycloak.cache.redis.authz.model.CachedEntityEnvelope;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;

/**
 * Cache-aside primitives for authorization entities backed by Redis strings (JSON).
 *
 * <p>Each entry is stored as a {@link CachedEntityEnvelope} containing the entity snapshot and the
 * generation (invalidation epoch) under which it was written. Reads compare the entry's generation
 * against the current per-resource-server generation key; entries older than the current generation
 * are treated as stale (miss).
 *
 * <p>All Redis failures are treated as cache misses (non-fatal) so that authorization remains
 * functional even when Redis is unavailable.
 */
public class RedisAuthorizationCache {

    private static final Logger LOG = Logger.getLogger(RedisAuthorizationCache.class);

    private final RedisConnectionProvider connection;
    private final ObjectMapper objectMapper;
    private final AuthorizationCacheConfig config;

    public RedisAuthorizationCache(
            RedisConnectionProvider connection,
            ObjectMapper objectMapper,
            AuthorizationCacheConfig config) {
        this.connection = connection;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public AuthorizationCacheConfig config() {
        return config;
    }

    /** The underlying Redis connection (exposed for subclasses). */
    protected RedisConnectionProvider connection() {
        return connection;
    }

    /**
     * Reads a cached entity, returning {@code null} on miss, stale entry, or Redis failure.
     *
     * @param key the full Redis key (from {@link AuthorizationCacheKey})
     * @param currentGen the current generation for the resource server (from {@link #currentGeneration})
     * @param type the concrete {@code Cached*} class to deserialize into
     */
    public <T> T get(String key, long currentGen, Class<T> type) {
        try {
            String json = connection.sync().get(key);
            RedisMetrics.record(RedisMetrics.Cache.AUTHZ, RedisMetrics.Op.GET);
            if (json == null || json.isEmpty()) {
                return null;
            }
            CachedEntityEnvelope envelope = objectMapper.readValue(json, CachedEntityEnvelope.class);
            if (envelope.getGen() < currentGen) {
                LOG.tracef("Stale cache entry for %s (entry gen=%d < current=%d)", key, envelope.getGen(), currentGen);
                return null;
            }
            return objectMapper.treeToValue(envelope.getPayload(), type);
        } catch (Exception e) {
            LOG.debugf(e, "Cache get failed for %s, treating as miss", key);
            return null;
        }
    }

    /**
     * Stores an entity snapshot with the given generation and a per-entity TTL.
     */
    public <T> void put(String key, long gen, T payload, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            CachedEntityEnvelope envelope =
                    new CachedEntityEnvelope(gen, objectMapper.valueToTree(payload));
            String json = objectMapper.writeValueAsString(envelope);
            connection.sync().set(key, json, SetArgs.Builder.ex(ttlSeconds));
            RedisMetrics.record(RedisMetrics.Cache.AUTHZ, RedisMetrics.Op.SET);
        } catch (Exception e) {
            LOG.debugf(e, "Cache put failed for %s", key);
        }
    }

    /** Convenience using the default TTL from config. */
    public <T> void put(String key, long gen, T payload) {
        put(key, gen, payload, config.getTtlSeconds());
    }

    /**
     * Removes a specific cache key.
     */
    public void remove(String key) {
        try {
            connection.sync().del(key);
            RedisMetrics.record(RedisMetrics.Cache.AUTHZ, RedisMetrics.Op.DEL);
        } catch (Exception e) {
            LOG.debugf(e, "Cache remove failed for %s", key);
        }
    }

    /**
     * Bumps the generation for a resource server, effectively invalidating all cached entries
     * belonging to that resource server. Called before and after writes.
     */
    public void invalidate(String resourceServerId) {
        if (resourceServerId == null) {
            return;
        }
        String genKey = AuthorizationCacheKey.generation(resourceServerId);
        try {
            connection.sync().incr(genKey);
            connection.sync().pexpire(genKey, config.getGenTtlSeconds() * 1000L);
            RedisMetrics.record(RedisMetrics.Cache.AUTHZ_GEN, RedisMetrics.Op.INCR);
            LOG.tracef("Invalidated authz cache for resource server %s", resourceServerId);
        } catch (Exception e) {
            LOG.debugf(e, "Cache invalidate failed for resource server %s", resourceServerId);
        }
    }

    /**
     * Returns the current generation for a resource server. {@code 0} when never invalidated (or on
     * Redis failure — treated as epoch zero, meaning all entries are considered fresh).
     */
    public long currentGeneration(String resourceServerId) {
        if (resourceServerId == null) {
            return 0L;
        }
        try {
            String val = connection.sync().get(AuthorizationCacheKey.generation(resourceServerId));
            if (val == null || val.isEmpty()) {
                return 0L;
            }
            return Long.parseLong(val.trim());
        } catch (Exception e) {
            LOG.debugf(e, "Failed to read generation for resource server %s", resourceServerId);
            return 0L;
        }
    }
}
