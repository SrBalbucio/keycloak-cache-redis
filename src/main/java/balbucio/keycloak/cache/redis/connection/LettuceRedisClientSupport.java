package balbucio.keycloak.cache.redis.connection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import balbucio.keycloak.cache.redis.RedisHashCas;
import balbucio.keycloak.cache.redis.common.ProviderHelpers;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;
import org.keycloak.Config;

/**
 * Shared Lettuce bootstrap used by {@code default} and {@code authz} connection factories.
 */
public final class LettuceRedisClientSupport {

    private static final Logger LOG = Logger.getLogger(LettuceRedisClientSupport.class);

    private RedisMode mode = RedisMode.STANDALONE;
    private AbstractRedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisClusterConnection<String, String> clusterConnection;
    private RedisSync sync;
    private RedisAsync async;
    private String casScriptSha;
    private String keyPrefix = "";
    private ClientResources clientResources;
    private boolean configureGlobalKeySpace;

    public LettuceRedisClientSupport(boolean configureGlobalKeySpace) {
        this.configureGlobalKeySpace = configureGlobalKeySpace;
    }

    public void init(Config.Scope config, String envNamePrefix) {
        mode = RedisMode.from(config.get("mode", "standalone"));

        String nodes = config.get("nodes");
        if (nodes == null || nodes.isBlank()) {
            throw new IllegalStateException(
                    envNamePrefix + "_NODES must contain at least one host:port");
        }
        List<ProviderHelpers.HostPort> hosts = ProviderHelpers.parseNodes(nodes);
        if (hosts.isEmpty()) {
            throw new IllegalStateException(
                    envNamePrefix + "_NODES must contain at least one host:port");
        }

        boolean ssl = Boolean.TRUE.equals(config.getBoolean("ssl", false));
        boolean sslVerifyPeer = Boolean.TRUE.equals(config.getBoolean("sslVerifyPeer", true));
        String username = config.get("username");
        String password = config.get("password");
        Duration timeout = ProviderHelpers.parseTimeout(config.get("timeout", "2000ms"), Duration.ofMillis(2000));
        int database = config.getInt("database", 0);
        String masterName = config.get("masterName");

        if (mode == RedisMode.SENTINEL && (masterName == null || masterName.isBlank())) {
            throw new IllegalStateException(
                    "Sentinel mode requires masterName. Set " + envNamePrefix + "_MASTER_NAME.");
        }

        String configuredPrefix = config.get("keyPrefix");
        if (configuredPrefix == null || configuredPrefix.isBlank()) {
            if (configureGlobalKeySpace) {
                configuredPrefix = System.getenv(RedisKeySpace.ENV_KEY_PREFIX);
            }
        }
        if (configureGlobalKeySpace) {
            RedisKeySpace.configure(configuredPrefix);
            keyPrefix = RedisKeySpace.prefix();
        } else {
            keyPrefix = normalizePrefix(configuredPrefix);
        }

        clientResources = buildClientResources();
        List<RedisURI> redisUris =
                buildUris(mode, hosts, ssl, sslVerifyPeer, username, password, timeout, database, masterName);

        switch (mode) {
            case CLUSTER -> initCluster(redisUris);
            case SENTINEL, STANDALONE -> initStandaloneOrSentinel(redisUris);
        }

        casScriptSha = RedisHashCas.load(sync);
        LOG.infof(
                "Redis connection initialized (mode=%s, nodes=%s, database=%d, keyPrefix='%s')",
                mode, nodes, mode == RedisMode.CLUSTER ? 0 : database, keyPrefix);
    }

    public RedisConnectionProvider asProvider() {
        return new RedisConnectionProvider() {
            @Override
            public RedisSync sync() {
                return sync;
            }

            @Override
            public RedisAsync async() {
                return async;
            }

            @Override
            public StatefulRedisPubSubConnection<String, String> connectPubSub() {
                return openPubSub();
            }

            @Override
            public RedisMode mode() {
                return mode;
            }

            @Override
            public String keyPrefix() {
                return keyPrefix;
            }

            @Override
            public String casScriptSha() {
                return casScriptSha;
            }

            @Override
            public void close() {
                // factory-owned
            }
        };
    }

    public void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (clusterConnection != null) {
            clusterConnection.close();
            clusterConnection = null;
        }
        if (client != null) {
            client.shutdown();
            client = null;
        }
        if (clientResources != null) {
            clientResources.shutdown();
            clientResources = null;
        }
        sync = null;
        async = null;
        casScriptSha = null;
    }

    private void initStandaloneOrSentinel(List<RedisURI> redisUris) {
        RedisClient redisClient = RedisClient.create(clientResources, redisUris.get(0));
        client = redisClient;
        connection = redisClient.connect();
        sync = LettuceRedisSync.of(connection.sync());
        async = LettuceRedisAsync.of(connection.async());
    }

    private void initCluster(List<RedisURI> redisUris) {
        RedisClusterClient clusterClient = RedisClusterClient.create(clientResources, redisUris);
        client = clusterClient;
        clusterConnection = clusterClient.connect();
        sync = LettuceRedisSync.of(clusterConnection.sync());
        async = LettuceRedisAsync.of(clusterConnection.async());
    }

    private StatefulRedisPubSubConnection<String, String> openPubSub() {
        if (mode == RedisMode.CLUSTER) {
            return ((RedisClusterClient) client).connectPubSub();
        }
        return ((RedisClient) client).connectPubSub();
    }

    private static ClientResources buildClientResources() {
        ClientResources.Builder builder = ClientResources.builder();
        try {
            MicrometerOptions options = MicrometerOptions.create();
            builder.commandLatencyRecorder(
                    new MicrometerCommandLatencyRecorder(Metrics.globalRegistry, options));
        } catch (RuntimeException e) {
            LOG.debugf("Micrometer latency recorder not available: %s", e.toString());
        }
        return builder.build();
    }

    private static List<RedisURI> buildUris(
            RedisMode mode,
            List<ProviderHelpers.HostPort> hosts,
            boolean ssl,
            boolean sslVerifyPeer,
            String username,
            String password,
            Duration timeout,
            int database,
            String masterName) {

        List<RedisURI> uris = new ArrayList<>();
        if (mode == RedisMode.SENTINEL) {
            RedisURI.Builder builder =
                    RedisURI.builder()
                            .withSentinelMasterId(masterName)
                            .withSsl(ssl)
                            .withVerifyPeer(ssl && sslVerifyPeer)
                            .withTimeout(timeout)
                            .withDatabase(database);
            for (ProviderHelpers.HostPort host : hosts) {
                builder.withSentinel(host.host(), host.port());
            }
            applyAuth(builder, username, password);
            uris.add(builder.build());
            return uris;
        }

        for (ProviderHelpers.HostPort host : hosts) {
            RedisURI.Builder builder =
                    RedisURI.builder()
                            .withHost(host.host())
                            .withPort(host.port())
                            .withSsl(ssl)
                            .withVerifyPeer(ssl && sslVerifyPeer)
                            .withTimeout(timeout);
            if (mode != RedisMode.CLUSTER) {
                builder.withDatabase(database);
            }
            applyAuth(builder, username, password);
            uris.add(builder.build());
            if (mode == RedisMode.STANDALONE) {
                break;
            }
        }
        return uris;
    }

    private static void applyAuth(RedisURI.Builder builder, String username, String password) {
        if (username != null && !username.isBlank()) {
            builder.withAuthentication(username, password == null ? "" : password);
        } else if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
    }

    private static String normalizePrefix(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) {
            return "";
        }
        String normalized = rawPrefix.trim();
        if (!normalized.endsWith(":")) {
            normalized = normalized + ":";
        }
        return normalized;
    }
}
