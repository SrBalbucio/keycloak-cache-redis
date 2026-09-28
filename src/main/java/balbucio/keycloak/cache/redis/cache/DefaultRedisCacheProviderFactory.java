package balbucio.keycloak.cache.redis.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.service.AutoService;

import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.connection.PubSubReconnect;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Factory of the public {@code redisCache} SPI (id {@code "redis"}). Holds, per Keycloak node:
 * one Jackson mapper, one PUBSUB subscriber multiplexing every namespace channel, and one
 * shared LRU map per namespace with LRU enabled.
 */
@AutoService(RedisCacheProviderFactory.class)
public class DefaultRedisCacheProviderFactory implements RedisCacheProviderFactory, IsSupported {

    private static final Logger LOG = Logger.getLogger(DefaultRedisCacheProviderFactory.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, DefaultRedisCache.LocalValue>> lruByNamespace =
            new ConcurrentHashMap<>();
    private final Set<String> subscribedChannels = ConcurrentHashMap.newKeySet();

    private volatile RedisConnectionProvider connectionProvider;
    private volatile StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile RedisConnectionStateListener reconnectListener;

    @Override
    public RedisCacheProvider create(KeycloakSession session) {
        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (redis == null) {
            throw new IllegalStateException("RedisConnectionProvider is required for redisCache");
        }
        if (connectionProvider == null) {
            synchronized (this) {
                if (connectionProvider == null) {
                    connectionProvider = redis;
                }
            }
        }
        return new DefaultRedisCacheProvider(this, redis);
    }

    /**
     * Returns (creating on first use) the node-shared LRU for {@code namespace}, wiring the
     * shared subscriber to its invalidation channel.
     */
    Map<String, DefaultRedisCache.LocalValue> lruFor(String namespace, int maxSize) {
        Map<String, DefaultRedisCache.LocalValue> lru =
                lruByNamespace.computeIfAbsent(
                        namespace, ns -> DefaultRedisCache.createSharedLru(Math.max(16, maxSize)));
        ensureSubscribed(namespace);
        return lru;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    private synchronized void ensureSubscribed(String namespace) {
        try {
            if (subscriber == null) {
                if (connectionProvider == null) {
                    return;
                }
                subscriber = connectionProvider.connectPubSub();
                subscriber.addListener(new RedisPubSubAdapter<String, String>() {
                    @Override
                    public void message(String channel, String message) {
                        onInvalidation(channel, message);
                    }
                });
                reconnectListener = PubSubReconnect.reconnectListener(
                        "cache-api", () -> lruByNamespace.values().forEach(Map::clear));
                PubSubReconnect.attach(subscriber, reconnectListener);
            }
            String channel = DefaultRedisCache.channelFor(namespace);
            if (subscribedChannels.add(channel)) {
                subscriber.sync().subscribe(channel);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to subscribe redisCache invalidation channel for %s", namespace);
        }
    }

    private void onInvalidation(String channel, String message) {
        String prefix = balbucio.keycloak.cache.redis.common.RedisKeySpace.key(
                DefaultRedisCache.INVALIDATION_PREFIX_RELATIVE);
        if (!channel.startsWith(prefix)) {
            return;
        }
        String namespace = channel.substring(prefix.length());
        Map<String, DefaultRedisCache.LocalValue> lru = lruByNamespace.get(namespace);
        if (lru != null && DefaultRedisCache.clearLocalKey(lru, message)) {
            LOG.tracef("redisCache LRU invalidated %s:%s", namespace, message);
        }
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (subscriber != null) {
            try {
                PubSubReconnect.detach(subscriber, reconnectListener);
                subscriber.close();
            } catch (Exception e) {
                LOG.debug("Error closing redisCache subscriber", e);
            }
            subscriber = null;
            reconnectListener = null;
        }
        lruByNamespace.clear();
        subscribedChannels.clear();
        connectionProvider = null;
    }

    @Override
    public String getId() {
        return "redis";
    }
}
