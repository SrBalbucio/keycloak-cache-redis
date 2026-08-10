package balbucio.keycloak.cache.redis.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import balbucio.keycloak.cache.redis.common.CommunityProfiles;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import com.google.auto.service.AutoService;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.cache.CacheRealmProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

@AutoService(CacheRealmProviderFactory.class)
public class RedisCacheRealmProviderFactory
        implements CacheRealmProviderFactory, EnvironmentDependentProviderFactory {

    private static final Logger LOG = Logger.getLogger(RedisCacheRealmProviderFactory.class);

    private EntityCacheConfig config;
    private Map<String, String> sharedL1;
    private StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile boolean pubsubInitialized;

    @Override
    public CacheRealmProvider create(KeycloakSession session) {
        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (sharedL1 == null) {
            sharedL1 = new ConcurrentHashMap<>();
        }
        if (!pubsubInitialized) {
            initPubSub(redis);
        }
        RedisEntityIndexCache cache =
                new RedisEntityIndexCache(redis, config.getTtlSeconds(), sharedL1);
        return RedisCacheRealmProvider.create(session, cache);
    }

    private synchronized void initPubSub(RedisConnectionProvider redis) {
        if (pubsubInitialized) {
            return;
        }
        try {
            if (redis == null) {
                pubsubInitialized = true;
                return;
            }
            String channel = RedisKeySpace.key(RedisEntityIndexCache.INVALIDATION_CHANNEL_RELATIVE);
            subscriber = redis.connectPubSub();
            Map<String, String> l1 = sharedL1;
            subscriber.addListener(
                    new RedisPubSubAdapter<String, String>() {
                        @Override
                        public void message(String ch, String message) {
                            if (channel.equals(ch) && l1 != null) {
                                if (message == null || "*".equals(message)) {
                                    l1.clear();
                                } else {
                                    l1.remove(message);
                                }
                            }
                        }
                    });
            subscriber.sync().subscribe(channel);
            LOG.infof("Entity cache (realm) PUBSUB subscriber initialized (channel=%s)", channel);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to init entity-cache PUBSUB for realm cache");
        } finally {
            pubsubInitialized = true;
        }
    }

    @Override
    public void init(Config.Scope scope) {
        config = EntityCacheConfig.load();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (subscriber != null) {
            try {
                subscriber.close();
            } catch (Exception ignored) {
                // ignore
            }
            subscriber = null;
        }
        if (sharedL1 != null) {
            sharedL1.clear();
        }
    }

    @Override
    public String getId() {
        return Constants.DEFAULT_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }

    @Override
    public boolean isSupported(Config.Scope scope) {
        if (!CommunityProfiles.isRedisCacheEnabled()) {
            return false;
        }
        EntityCacheConfig cfg = config != null ? config : EntityCacheConfig.load();
        return cfg.isEnabled();
    }
}
