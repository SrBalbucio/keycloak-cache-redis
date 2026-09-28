package balbucio.keycloak.cache.redis.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;

/**
 * Default {@link RedisCache} implementation: Redis (L2) + optional node-local LRU (L1) with
 * PUBSUB invalidation, String/JSON values, fail-open I/O.
 */
final class DefaultRedisCache implements RedisCache {

    private static final Logger LOG = Logger.getLogger(DefaultRedisCache.class);

    static final String DATA_PREFIX_RELATIVE = "cache-api:";
    static final String INVALIDATION_PREFIX_RELATIVE = "cache-api:invalidate:";
    static final String CLEAR_ALL_MESSAGE = "*";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final String namespace;
    private final RedisCacheConfig config;
    private final RedisConnectionProvider connection;
    private final ObjectMapper objectMapper;
    /** Node-shared LRU for this namespace, or {@code null} when L1 is disabled. */
    private final Map<String, LocalValue> lru;
    private final String invalidationChannel;

    DefaultRedisCache(
            String namespace,
            RedisCacheConfig config,
            RedisConnectionProvider connection,
            ObjectMapper objectMapper,
            Map<String, LocalValue> lru) {
        this.namespace = namespace;
        this.config = config;
        this.connection = connection;
        this.objectMapper = objectMapper;
        this.lru = lru;
        this.invalidationChannel = channelFor(namespace);
    }

    static String validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid redisCache namespace '" + namespace
                            + "': use 1-64 chars of [a-z0-9_-]");
        }
        return namespace;
    }

    static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("redisCache key must not be blank");
        }
        if (key.contains(CLEAR_ALL_MESSAGE)) {
            throw new IllegalArgumentException(
                    "redisCache key must not contain '*' (reserved for broadcast clear)");
        }
        return key;
    }

    static String channelFor(String namespace) {
        return RedisKeySpace.key(INVALIDATION_PREFIX_RELATIVE + namespace);
    }

    static String redisKey(String namespace, String key) {
        return RedisKeySpace.key(DATA_PREFIX_RELATIVE + namespace + ":" + key);
    }

    /** Clears one L1 key ({@code "*"} clears the whole map). Returns true when it held something. */
    static boolean clearLocalKey(Map<String, LocalValue> map, String message) {
        if (map == null) {
            return false;
        }
        if (message == null || message.isBlank() || CLEAR_ALL_MESSAGE.equals(message)) {
            boolean had = !map.isEmpty();
            map.clear();
            return had;
        }
        return map.remove(message) != null;
    }

    static Map<String, LocalValue> createSharedLru(int maxSize) {
        return Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, LocalValue> eldest) {
                        return size() > maxSize;
                    }
                });
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        validateKey(key);
        if (type == null) {
            throw new IllegalArgumentException("redisCache type must not be null");
        }
        if (lru != null) {
            LocalValue local = lru.get(key);
            if (local != null) {
                if (local.type == type && local.expireAt > System.currentTimeMillis()) {
                    RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.HIT);
                    return type.cast(local.value);
                }
                lru.remove(key);
            }
        }
        String raw;
        try {
            raw = connection.sync().get(redisKey(namespace, key));
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.GET);
        } catch (Exception e) {
            LOG.debugf(e, "redisCache get failed for %s:%s", namespace, key);
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.ERROR);
            return null;
        }
        if (raw == null) {
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.MISS);
            return null;
        }
        T value = decode(key, raw, type);
        if (value == null) {
            return null;
        }
        if (lru != null) {
            lru.put(key, new LocalValue(value, type, lruExpireAt()));
        }
        return value;
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> type, long ttlSeconds, Callable<T> loader)
            throws Exception {
        T cached = get(key, type);
        if (cached != null) {
            return cached;
        }
        if (loader == null) {
            return null;
        }
        T loaded = loader.call();
        if (loaded != null) {
            put(key, loaded, ttlSeconds);
        }
        return loaded;
    }

    @Override
    public <T> void put(String key, T value, long ttlSeconds) {
        validateKey(key);
        if (value == null) {
            remove(key);
            return;
        }
        String encoded = encode(key, value);
        try {
            if (ttlSeconds > 0) {
                connection.sync().set(redisKey(namespace, key), encoded, SetArgs.Builder.ex(ttlSeconds));
            } else {
                connection.sync().set(redisKey(namespace, key), encoded);
            }
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.SET);
        } catch (Exception e) {
            LOG.debugf(e, "redisCache put failed for %s:%s", namespace, key);
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.ERROR);
            return;
        }
        if (lru != null) {
            @SuppressWarnings("unchecked")
            Class<T> type = (Class<T>) value.getClass();
            lru.put(key, new LocalValue(value, type, lruExpireAt()));
        }
        broadcast(key);
    }

    @Override
    public void remove(String key) {
        validateKey(key);
        if (lru != null) {
            lru.remove(key);
        }
        try {
            connection.sync().del(redisKey(namespace, key));
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.DEL);
        } catch (Exception e) {
            LOG.debugf(e, "redisCache remove failed for %s:%s", namespace, key);
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.ERROR);
        }
        broadcast(key);
    }

    @Override
    public void clear() {
        if (lru != null) {
            lru.clear();
        }
        broadcast(CLEAR_ALL_MESSAGE);
    }

    private void broadcast(String message) {
        try {
            connection.sync().publish(invalidationChannel, message);
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.PUBLISH);
        } catch (Exception e) {
            LOG.debugf(e, "redisCache invalidation broadcast failed for %s", namespace);
        }
    }

    /**
     * Node-local entries never outlive {@code lruTtlSeconds} (default 60s), whatever the Redis
     * TTL is. This bounds cross-node staleness when an invalidation broadcast is missed, at the
     * cost of at most one Redis read per key per window on hot paths.
     */
    private long lruExpireAt() {
        return System.currentTimeMillis() + Math.max(1L, config.getLruTtlSeconds()) * 1000L;
    }

    private <T> String encode(String key, T value) {
        try {
            String payload =
                    value instanceof String s ? s : objectMapper.writeValueAsString(value);
            return value.getClass().getName() + "\n" + payload;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "redisCache value for " + namespace + ":" + key + " is not serializable", e);
        }
    }

    private <T> T decode(String key, String raw, Class<T> type) {
        int sep = raw.indexOf('\n');
        if (sep < 0 || !raw.substring(0, sep).equals(type.getName())) {
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.MISS);
            return null;
        }
        String payload = raw.substring(sep + 1);
        try {
            T value = type == String.class
                    ? type.cast(payload)
                    : objectMapper.readValue(payload, type);
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.HIT);
            return value;
        } catch (Exception e) {
            LOG.debugf(e, "redisCache decode failed for %s:%s", namespace, key);
            RedisMetrics.record(RedisMetrics.Cache.CACHE_API, RedisMetrics.Op.ERROR);
            return null;
        }
    }

    /** Node-local LRU entry: deserialized value, its runtime type and local expiry. */
    static final class LocalValue {
        final Object value;
        final Class<?> type;
        final long expireAt;

        LocalValue(Object value, Class<?> type, long expireAt) {
            this.value = value;
            this.type = type;
            this.expireAt = expireAt;
        }
    }
}
