package balbucio.keycloak.cache.redis.authz.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.authz.AuthorizationCacheConfig;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.jboss.logging.Logger;

/**
 * Extends {@link RedisAuthorizationCache} with an optional node-local LRU layer that avoids Redis
 * round-trips for hot authz keys (resource/policy by-id during policy evaluation).
 *
 * <p>The LRU map is shared across all sessions on this node (created once in the factory). On
 * {@link #invalidate}, the local LRU is cleared and a PUBSUB message is published to a dedicated
 * channel so other nodes clear their local LRU too.
 *
 * <p>Staleness is bounded by two mechanisms:
 * <ul>
 *   <li>Local entry TTL (default 30s) — entries auto-expire from the local cache.
 *   <li>PUBSUB broadcast on writes — other nodes clear their LRU immediately.
 * </ul>
 */
public class LocalAuthorizationCache extends RedisAuthorizationCache {

    private static final Logger LOG = Logger.getLogger(LocalAuthorizationCache.class);

    public static final String INVALIDATION_CHANNEL_RELATIVE = "authz:invalidation";

    private final Map<String, LocalEntry> sharedLru;
    private final long localTtlMillis;
    private final String invalidationChannel;

    public LocalAuthorizationCache(
            RedisConnectionProvider connection,
            ObjectMapper objectMapper,
            AuthorizationCacheConfig config,
            Map<String, LocalEntry> sharedLru) {
        super(connection, objectMapper, config);
        this.sharedLru = sharedLru;
        this.localTtlMillis = config.getLruTtlSeconds() * 1000L;
        this.invalidationChannel = RedisKeySpace.key(INVALIDATION_CHANNEL_RELATIVE);
    }

    @Override
    public <T> T get(String key, long currentGen, Class<T> type) {
        LocalEntry local = sharedLru.get(key);
        if (local != null && local.type == type && local.gen >= currentGen) {
            long now = System.currentTimeMillis();
            if (local.expireAt > now) {
                LOG.tracef("Local LRU hit for %s", key);
                return type.cast(local.value);
            }
            sharedLru.remove(key);
        }
        T value = super.get(key, currentGen, type);
        if (value != null) {
            sharedLru.put(
                    key,
                    new LocalEntry(value, type, currentGen, System.currentTimeMillis() + localTtlMillis));
        }
        return value;
    }

    @Override
    public <T> void put(String key, long gen, T payload, long ttlSeconds) {
        super.put(key, gen, payload, ttlSeconds);
        if (payload != null) {
            sharedLru.put(
                    key,
                    new LocalEntry(
                            payload,
                            payload.getClass(),
                            gen,
                            System.currentTimeMillis() + localTtlMillis));
        }
    }

    @Override
    public void remove(String key) {
        sharedLru.remove(key);
        super.remove(key);
    }

    @Override
    public void invalidate(String resourceServerId) {
        sharedLru.clear();
        super.invalidate(resourceServerId);
        broadcastInvalidation();
    }

    /**
     * Clears the local LRU without touching Redis. Called when a PUBSUB invalidation message
     * arrives from another node.
     */
    public void clearLocal() {
        sharedLru.clear();
    }

    /** Publishes an invalidation notice so other nodes clear their local LRU. */
    private void broadcastInvalidation() {
        try {
            Long delivered = connection().sync().publish(invalidationChannel, "invalidate");
            RedisMetrics.record(RedisMetrics.Cache.AUTHZ, RedisMetrics.Op.PUBLISH);
            LOG.tracef("Broadcast authz LRU invalidation to %d subscribers", delivered);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to broadcast authz LRU invalidation");
        }
    }

    /** Returns the Redis connection (inherited from base; used for PUBSUB publish). */
    public String invalidationChannel() {
        return invalidationChannel;
    }

    /**
     * Creates a bounded synchronized LRU map suitable for sharing across sessions on one node.
     */
    public static Map<String, LocalEntry> createSharedLru(int maxSize) {
        return Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, LocalEntry> eldest) {
                        return size() > maxSize;
                    }
                });
    }

    /** An entry in the local LRU: the deserialized value, its type, generation, and local expiry. */
    public static final class LocalEntry {
        final Object value;
        final Class<?> type;
        final long gen;
        final long expireAt;

        LocalEntry(Object value, Class<?> type, long gen, long expireAt) {
            this.value = value;
            this.type = type;
            this.gen = gen;
            this.expireAt = expireAt;
        }
    }
}
