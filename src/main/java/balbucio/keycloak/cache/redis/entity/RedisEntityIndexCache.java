package balbucio.keycloak.cache.redis.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;

/**
 * Shared L2 Redis + L1 map for entity lookup indexes (username→id, realm name→id, …).
 */
public final class RedisEntityIndexCache {

    private static final Logger LOG = Logger.getLogger(RedisEntityIndexCache.class);

    public static final String INVALIDATION_CHANNEL_RELATIVE = "entity:invalidation";
    public static final String KEY_PREFIX_RELATIVE = "entity:";

    private final RedisConnectionProvider connection;
    private final long ttlSeconds;
    private final Map<String, String> localL1;
    private final String invalidationChannel;

    public RedisEntityIndexCache(
            RedisConnectionProvider connection, long ttlSeconds, Map<String, String> localL1) {
        this.connection = connection;
        this.ttlSeconds = ttlSeconds;
        this.localL1 = localL1 != null ? localL1 : new ConcurrentHashMap<>();
        this.invalidationChannel = RedisKeySpace.key(INVALIDATION_CHANNEL_RELATIVE);
    }

    public String get(String relativeKey) {
        String local = localL1.get(relativeKey);
        if (local != null) {
            RedisMetrics.record(RedisMetrics.Cache.ENTITY, RedisMetrics.Op.GET);
            return local;
        }
        if (connection == null) {
            return null;
        }
        try {
            String value = connection.sync().get(redisKey(relativeKey));
            if (value != null) {
                localL1.put(relativeKey, value);
                RedisMetrics.record(RedisMetrics.Cache.ENTITY, RedisMetrics.Op.GET);
            }
            return value;
        } catch (Exception e) {
            LOG.debugf(e, "Entity cache get failed for %s", relativeKey);
            return null;
        }
    }

    public void put(String relativeKey, String value) {
        if (value == null) {
            return;
        }
        localL1.put(relativeKey, value);
        if (connection == null) {
            return;
        }
        try {
            RedisSync sync = connection.sync();
            if (ttlSeconds > 0) {
                sync.set(redisKey(relativeKey), value, SetArgs.Builder.ex(ttlSeconds));
            } else {
                sync.set(redisKey(relativeKey), value);
            }
            RedisMetrics.record(RedisMetrics.Cache.ENTITY, RedisMetrics.Op.SET);
        } catch (Exception e) {
            LOG.debugf(e, "Entity cache put failed for %s", relativeKey);
        }
    }

    public void remove(String relativeKey) {
        localL1.remove(relativeKey);
        if (connection == null) {
            return;
        }
        try {
            connection.sync().del(redisKey(relativeKey));
            RedisMetrics.record(RedisMetrics.Cache.ENTITY, RedisMetrics.Op.DEL);
        } catch (Exception e) {
            LOG.debugf(e, "Entity cache del failed for %s", relativeKey);
        }
    }

    public void clearLocal() {
        localL1.clear();
    }

    public void clearAllAndBroadcast() {
        localL1.clear();
        broadcast("*");
    }

    public void invalidate(String relativeKey) {
        remove(relativeKey);
        broadcast(relativeKey == null ? "*" : relativeKey);
    }

    public void clearLocalKey(String relativeKey) {
        if (relativeKey == null || "*".equals(relativeKey)) {
            localL1.clear();
        } else {
            localL1.remove(relativeKey);
        }
    }

    public String invalidationChannel() {
        return invalidationChannel;
    }

    private void broadcast(String message) {
        if (connection == null) {
            return;
        }
        try {
            connection.sync().publish(invalidationChannel, message);
            RedisMetrics.record(RedisMetrics.Cache.ENTITY, RedisMetrics.Op.PUBLISH);
        } catch (Exception e) {
            LOG.debugf(e, "Entity cache invalidation publish failed");
        }
    }

    static String redisKey(String relative) {
        return RedisKeySpace.key(KEY_PREFIX_RELATIVE + relative);
    }

    public static String userByUsername(String realmId, String username) {
        return "user-by-username:" + realmId + ":" + username.toLowerCase();
    }

    public static String userByEmail(String realmId, String email) {
        return "user-by-email:" + realmId + ":" + email.toLowerCase();
    }

    public static String userById(String realmId, String userId) {
        return "user:" + realmId + ":" + userId;
    }

    public static String realmByName(String name) {
        return "realm-by-name:" + name;
    }

    public static String clientByClientId(String realmId, String clientId) {
        return "client-by-clientId:" + realmId + ":" + clientId;
    }
}
