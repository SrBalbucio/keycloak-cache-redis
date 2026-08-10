package balbucio.keycloak.cache.redis.compatibility;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.SetArgs;
import org.jboss.logging.Logger;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;
import org.keycloak.keys.PublicKeyStorageProvider;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;

/**
 * Public-key storage with Redis L2, node-local L1, and PUBSUB invalidation.
 *
 * <p>Fail-open: Redis errors fall back to {@link PublicKeyLoader#loadKeys()} so federated login is
 * never blocked by cache outages.
 */
public class RedisPublicKeyStorageProvider implements PublicKeyStorageProvider {

    private static final Logger LOG = Logger.getLogger(RedisPublicKeyStorageProvider.class);

    public static final String KEY_PREFIX_RELATIVE = "public-keys:";
    public static final String INVALIDATION_CHANNEL_RELATIVE = "public-keys:invalidation";

    private final RedisConnectionProvider connection;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final Map<String, PublicKeysWrapper> localL1;
    private final String invalidationChannel;

    public RedisPublicKeyStorageProvider(
            RedisConnectionProvider connection,
            ObjectMapper objectMapper,
            long ttlSeconds,
            Map<String, PublicKeysWrapper> localL1) {
        this.connection = connection;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.localL1 = localL1 != null ? localL1 : new ConcurrentHashMap<>();
        this.invalidationChannel = RedisKeySpace.key(INVALIDATION_CHANNEL_RELATIVE);
    }

    @Override
    public KeyWrapper getPublicKey(String modelKey, String kid, String algorithm, PublicKeyLoader loader) {
        return getOrLoad(modelKey, loader).getKeyByKidAndAlg(kid, algorithm);
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, String algorithm, PublicKeyLoader loader) {
        return getOrLoad(modelKey, loader).getKeyByKidAndAlg(null, algorithm);
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, Predicate<KeyWrapper> predicate, PublicKeyLoader loader) {
        return getOrLoad(modelKey, loader).getKeys().stream().filter(predicate).findFirst().orElse(null);
    }

    @Override
    public List<KeyWrapper> getKeys(String modelKey, PublicKeyLoader loader) {
        return getOrLoad(modelKey, loader).getKeys();
    }

    @Override
    public boolean reloadKeys(String modelKey, PublicKeyLoader loader) {
        try {
            PublicKeysWrapper loaded = loader.loadKeys();
            if (loaded == null) {
                loaded = PublicKeysWrapper.EMPTY;
            }
            store(modelKey, loaded);
            broadcastInvalidation(modelKey);
            RedisMetrics.record(RedisMetrics.Cache.PUBLIC_KEYS, RedisMetrics.Op.SET);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reload public keys for " + modelKey, e);
        }
    }

    /** Clears L1 for a model key (or all keys when message is blank / {@code *}). */
    public void clearLocal(String modelKey) {
        if (modelKey == null || modelKey.isBlank() || "*".equals(modelKey)) {
            localL1.clear();
        } else {
            localL1.remove(modelKey);
        }
    }

    public String invalidationChannel() {
        return invalidationChannel;
    }

    private PublicKeysWrapper getOrLoad(String modelKey, PublicKeyLoader loader) {
        PublicKeysWrapper local = localL1.get(modelKey);
        if (local != null) {
            RedisMetrics.record(RedisMetrics.Cache.PUBLIC_KEYS, RedisMetrics.Op.GET);
            return local;
        }

        PublicKeysWrapper fromRedis = readRedis(modelKey);
        if (fromRedis != null) {
            localL1.put(modelKey, fromRedis);
            RedisMetrics.record(RedisMetrics.Cache.PUBLIC_KEYS, RedisMetrics.Op.GET);
            return fromRedis;
        }

        synchronized (this) {
            local = localL1.get(modelKey);
            if (local != null) {
                return local;
            }
            try {
                PublicKeysWrapper loaded = loader.loadKeys();
                if (loaded == null) {
                    loaded = PublicKeysWrapper.EMPTY;
                }
                store(modelKey, loaded);
                RedisMetrics.record(RedisMetrics.Cache.PUBLIC_KEYS, RedisMetrics.Op.SET);
                return loaded;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load public keys for " + modelKey, e);
            }
        }
    }

    private PublicKeysWrapper readRedis(String modelKey) {
        if (connection == null) {
            return null;
        }
        try {
            String json = connection.sync().get(redisKey(modelKey));
            if (json == null || json.isBlank()) {
                return null;
            }
            CachedPublicKeys cached = objectMapper.readValue(json, CachedPublicKeys.class);
            return PublicKeyCodec.fromCached(cached);
        } catch (Exception e) {
            LOG.debugf(e, "Redis public-key read failed for %s — fail-open to loader", modelKey);
            return null;
        }
    }

    private void store(String modelKey, PublicKeysWrapper wrapper) {
        localL1.put(modelKey, wrapper);
        if (connection == null) {
            return;
        }
        try {
            CachedPublicKeys cached = PublicKeyCodec.toCached(wrapper);
            String json = objectMapper.writeValueAsString(cached);
            RedisSync sync = connection.sync();
            if (ttlSeconds > 0) {
                sync.set(redisKey(modelKey), json, SetArgs.Builder.ex(ttlSeconds));
            } else {
                sync.set(redisKey(modelKey), json);
            }
        } catch (Exception e) {
            LOG.debugf(e, "Redis public-key write failed for %s — keeping L1 only", modelKey);
        }
    }

    private void broadcastInvalidation(String modelKey) {
        if (connection == null) {
            return;
        }
        try {
            connection.sync().publish(invalidationChannel, modelKey == null ? "*" : modelKey);
            RedisMetrics.record(RedisMetrics.Cache.PUBLIC_KEYS, RedisMetrics.Op.PUBLISH);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to broadcast public-key invalidation");
        }
    }

    static String redisKey(String modelKey) {
        return RedisKeySpace.key(KEY_PREFIX_RELATIVE + modelKey);
    }

    @Override
    public void close() {
        localL1.clear();
    }
}
