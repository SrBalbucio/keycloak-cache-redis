package balbucio.keycloak.cache.redis.authz;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.service.AutoService;

import balbucio.keycloak.cache.redis.authz.cache.LocalAuthorizationCache;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.authorization.CachedStoreProviderFactory;

/**
 * Replaces the built-in Infinispan authorization cache with a Redis cache-aside layer.
 *
 * <p>Registered as {@code id="default"} with priority above the Infinispan factory so it wins when
 * the Redis cache extension is active. The underlying JPA {@link
 * org.keycloak.authorization.store.StoreFactory} is always the source of truth.
 *
 * <p>When {@link AuthorizationCacheConfig#isEnabled()} is {@code false}, the provider delegates
 * directly to JPA with no caching (equivalent to the former
 * {@code NullCachedStoreProviderFactory}).
 *
 * <p>When {@link AuthorizationCacheConfig#isLruEnabled()} is {@code true} (Phase A4), an optional
 * node-local LRU layer is activated with PUBSUB cross-node invalidation.
 */
@AutoService(CachedStoreProviderFactory.class)
public class RedisCachedStoreProviderFactory implements CachedStoreProviderFactory, IsSupported {

    private static final Logger LOG = Logger.getLogger(RedisCachedStoreProviderFactory.class);

    private AuthorizationCacheConfig config;
    private ObjectMapper objectMapper;

    private Map<String, LocalAuthorizationCache.LocalEntry> sharedLocalLru;
    private StatefulRedisPubSubConnection<String, String> lruSubscriber;
    private volatile boolean pubsubInitialized = false;

    @Override
    public RedisCachedStoreFactoryProvider create(KeycloakSession session) {
        if (config.isLruEnabled() && !pubsubInitialized) {
            initPubSubSubscriber(session);
        }
        return new RedisCachedStoreFactoryProvider(
                session,
                session.getProvider(org.keycloak.authorization.store.StoreFactory.class),
                config,
                objectMapper,
                sharedLocalLru);
    }

    private synchronized void initPubSubSubscriber(KeycloakSession session) {
        if (pubsubInitialized) {
            return;
        }
        try {
            RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
            if (redis == null) {
                LOG.warn("RedisConnectionProvider unavailable — authz local LRU will not receive cross-node invalidation");
                pubsubInitialized = true;
                return;
            }

            sharedLocalLru = LocalAuthorizationCache.createSharedLru(config.getLruMaxSize());

            String channel = balbucio.keycloak.cache.redis.common.RedisKeySpace.key(
                    LocalAuthorizationCache.INVALIDATION_CHANNEL_RELATIVE);

            lruSubscriber = redis.connectPubSub();
            lruSubscriber.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String ch, String message) {
                    if (channel.equals(ch) && sharedLocalLru != null) {
                        LOG.trace("Received authz LRU invalidation from another node — clearing local LRU");
                        sharedLocalLru.clear();
                    }
                }
            });
            lruSubscriber.sync().subscribe(channel);
            LOG.infof("Authz local LRU PUBSUB subscriber initialized (channel=%s, max=%d, ttl=%ds)",
                    channel, config.getLruMaxSize(), config.getLruTtlSeconds());
        } catch (Exception e) {
            LOG.warnf(e, "Failed to initialize authz LRU PUBSUB subscriber — local LRU will not receive cross-node invalidation");
        } finally {
            pubsubInitialized = true;
        }
    }

    @Override
    public void init(Config.Scope config) {
        this.config = AuthorizationCacheConfig.load();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (lruSubscriber != null) {
            try {
                lruSubscriber.close();
            } catch (Exception e) {
                LOG.debug("Error closing authz LRU subscriber", e);
            }
            lruSubscriber = null;
        }
        if (sharedLocalLru != null) {
            sharedLocalLru.clear();
            sharedLocalLru = null;
        }
        pubsubInitialized = false;
    }

    @Override
    public String getId() {
        return Constants.DEFAULT_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }
}
