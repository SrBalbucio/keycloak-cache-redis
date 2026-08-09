package balbucio.keycloak.cache.redis.cluster;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import com.google.auto.service.AutoService;
import io.lettuce.core.SetArgs;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ClusterProviderFactory;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@AutoService(ClusterProviderFactory.class)
public class RedisPubsubClusterProviderFactory implements ClusterProviderFactory, IsSupported {

    private static final Logger LOG = Logger.getLogger(RedisPubsubClusterProviderFactory.class);

    static final String CLUSTER_START_RELATIVE = "cluster:startTime";

    private volatile ClusterProvider clusterProvider;
    private StatefulRedisPubSubConnection<String, String> subscriber;
    private final String nodeId = UUID.randomUUID().toString();

    private final ExecutorService localExecutor =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread thread = Executors.defaultThreadFactory().newThread(r);
                        thread.setName(getClass().getName() + "-" + thread.getName());
                        thread.setDaemon(true);
                        return thread;
                    });

    @Override
    public ClusterProvider create(KeycloakSession session) {
        return lazyInit(session);
    }

    private synchronized ClusterProvider lazyInit(KeycloakSession session) {
        if (clusterProvider != null) {
            return clusterProvider;
        }

        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (redis == null) {
            throw new IllegalStateException("RedisConnectionProvider is required for ClusterProvider");
        }

        RedisSync publisher = redis.sync();
        subscriber = redis.connectPubSub();

        int clusterStartTime = initClusterStartTime(session, publisher);
        clusterProvider =
                new RedisPubsubClusterProvider(
                        publisher, subscriber, clusterStartTime, localExecutor, nodeId);
        LOG.infof(
                "Redis ClusterProvider initialized (node=%s, startTime=%d)", nodeId, clusterStartTime);
        return clusterProvider;
    }

    protected int initClusterStartTime(KeycloakSession session, RedisSync client) {
        String startKey = RedisKeySpace.key(CLUSTER_START_RELATIVE);
        try {
            String existing = client.get(startKey);
            if (existing != null) {
                int existingClusterStartTime = Integer.parseInt(existing);
                LOG.debugf("Loaded cluster start time: %s", Time.toDate(existingClusterStartTime));
                return existingClusterStartTime;
            }
            int serverStartTime =
                    (int) (session.getKeycloakSessionFactory().getServerStartupTimestamp() / 1000);
            String result =
                    client.set(startKey, String.valueOf(serverStartTime), SetArgs.Builder.nx());
            if ("OK".equals(result)) {
                return serverStartTime;
            }
            return Integer.parseInt(client.get(startKey));
        } catch (Exception e) {
            throw new IllegalStateException("Error getting cluster start time", e);
        }
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        try {
            if (clusterProvider != null) {
                clusterProvider.close();
                clusterProvider = null;
            }
            subscriber = null;
        } catch (Exception e) {
            LOG.warn("Error closing Redis ClusterProvider", e);
        }
        localExecutor.shutdownNow();
    }

    @Override
    public String getId() {
        return Constants.INFINISPAN_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY + 1;
    }
}
