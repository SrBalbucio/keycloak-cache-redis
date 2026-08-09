package balbucio.keycloak.cache.redis.connection;

import java.time.Duration;
import java.util.List;

import balbucio.keycloak.cache.redis.RedisHashCas;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import balbucio.keycloak.cache.redis.common.ProviderHelpers;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import com.google.auto.service.AutoService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@AutoService(RedisConnectionProviderFactory.class)
public class DefaultRedisConnectionProviderFactory implements RedisConnectionProviderFactory, IsSupported {

    private static final Logger LOG = Logger.getLogger(DefaultRedisConnectionProviderFactory.class);

    private RedisMode mode = RedisMode.STANDALONE;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private String casScriptSha;
    private String keyPrefix = "";

    @Override
    public RedisConnectionProvider create(KeycloakSession session) {
        return new RedisConnectionProvider() {
            @Override
            public RedisCommands<String, String> sync() {
                return connection.sync();
            }

            @Override
            public RedisAsyncCommands<String, String> async() {
                return connection.async();
            }

            @Override
            public StatefulRedisPubSubConnection<String, String> pubSub() {
                if (pubSubConnection == null) {
                    pubSubConnection = client.connectPubSub();
                }
                return pubSubConnection;
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
                // shared client — closed by factory
            }
        };
    }

    @Override
    public void init(Config.Scope config) {
        mode = RedisMode.from(config.get("mode", "standalone"));
        if (mode != RedisMode.STANDALONE) {
            throw new IllegalStateException(
                    "Redis mode '"
                            + mode
                            + "' is not supported in this MVP. Use standalone (sentinel/cluster planned for a later phase).");
        }

        String nodes = config.get("nodes", "redis:6379");
        List<ProviderHelpers.HostPort> hosts = ProviderHelpers.parseNodes(nodes);
        if (hosts.isEmpty()) {
            throw new IllegalStateException("KC_SPI_REDIS_CONNECTION_DEFAULT_NODES must contain at least one host:port");
        }
        ProviderHelpers.HostPort first = hosts.get(0);

        boolean ssl = Boolean.TRUE.equals(config.getBoolean("ssl", false));
        String username = config.get("username");
        String password = config.get("password");
        Duration timeout = ProviderHelpers.parseTimeout(config.get("timeout", "2000ms"), Duration.ofMillis(2000));
        int database = config.getInt("database", 0);

        String configuredPrefix = config.get("keyPrefix");
        if (configuredPrefix == null || configuredPrefix.isBlank()) {
            configuredPrefix = System.getenv(RedisKeySpace.ENV_KEY_PREFIX);
        }
        RedisKeySpace.configure(configuredPrefix);
        keyPrefix = RedisKeySpace.prefix();

        RedisURI.Builder builder =
                RedisURI.builder()
                        .withHost(first.host())
                        .withPort(first.port())
                        .withSsl(ssl)
                        .withDatabase(database)
                        .withTimeout(timeout);

        if (username != null && !username.isBlank()) {
            builder.withAuthentication(username, password == null ? "" : password);
        } else if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }

        RedisURI uri = builder.build();
        client = RedisClient.create(uri);
        connection = client.connect();
        casScriptSha = RedisHashCas.load(connection.sync());
        LOG.infof(
                "Redis connection initialized (mode=%s, node=%s:%d, database=%d, keyPrefix='%s')",
                mode, first.host(), first.port(), database, keyPrefix);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
            pubSubConnection = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (client != null) {
            client.shutdown();
            client = null;
        }
        casScriptSha = null;
    }

    @Override
    public String getId() {
        return Constants.DEFAULT_PROVIDER_ID;
    }
}
