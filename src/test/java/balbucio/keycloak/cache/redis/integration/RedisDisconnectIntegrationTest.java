package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.connection.LettuceRedisAsync;
import balbucio.keycloak.cache.redis.connection.LettuceRedisSync;
import balbucio.keycloak.cache.redis.connection.RedisAsync;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisMode;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import balbucio.keycloak.cache.redis.userSession.RedisUserSessionProvider;
import balbucio.keycloak.cache.redis.userSession.UserSessionKey;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionModel.SessionPersistenceState;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis outage coverage on a DEDICATED container (never the shared {@link IntegrationRedis}
 * one): operations must fail fast while Redis is unreachable, Lettuce must reconnect on its own
 * once Redis is back, and sessions written before the outage must survive a pause.
 */
@Tag("chaos")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisDisconnectIntegrationTest extends AbstractRedisIntegrationTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private RedisContainer container;
    private ClientResources clientResources;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisConnectionProvider provider;

    @BeforeAll
    void startDedicatedRedis() throws Exception {
        // Fixed host port: on some Docker hosts (e.g. Docker Desktop/Windows) a container
        // stop/start reassigns the *dynamic* mapped port, which would masquerade as a
        // reconnect failure. A fixed binding survives the restart.
        int hostPort = findFreePort();
        container = new FixedPortRedisContainer(hostPort);
        container.start();
        RedisURI uri =
                RedisURI.builder()
                        .withHost(container.getHost())
                        .withPort(hostPort)
                        .withTimeout(COMMAND_TIMEOUT)
                        .build();
        // Aggressive, deterministic reconnect so the outage window stays short in tests:
        // retry every 500ms instead of the default exponential backoff (up to 30s).
        clientResources =
                DefaultClientResources.builder()
                        .reconnectDelay(Delay.constant(Duration.ofMillis(500)))
                        .build();
        client = RedisClient.create(clientResources, uri);
        // Reject commands while the connection is down (fail fast instead of buffering).
        client.setOptions(
                ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
                        .build());
        connection = client.connect();
        provider =
                new RedisConnectionProvider() {
                    @Override
                    public RedisSync sync() {
                        return LettuceRedisSync.of(connection.sync());
                    }

                    @Override
                    public RedisAsync async() {
                        return LettuceRedisAsync.of(connection.async());
                    }

                    @Override
                    public StatefulRedisPubSubConnection<String, String> connectPubSub() {
                        return client.connectPubSub();
                    }

                    @Override
                    public RedisMode mode() {
                        return RedisMode.STANDALONE;
                    }

                    @Override
                    public String keyPrefix() {
                        return "";
                    }

                    @Override
                    public String casScriptSha() {
                        return null;
                    }

                    @Override
                    public void close() {}
                };
    }

    @AfterAll
    void stopDedicatedRedis() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (client != null) {
            try {
                client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (clientResources != null) {
            try {
                clientResources.shutdown(0, 2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (container != null) {
            try {
                container.stop();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /** Chaos tests manage their own container; do not touch (or even start) the shared one. */
    @Override
    @BeforeEach
    void flushRedis() {
        awaitRedisUp();
        connection.sync().flushdb();
    }

    @Test
    void operationsFailFastWhileRedisIsFrozen() {
        createSession("outage-1", "before");

        pauseRedis();
        try {
            assertThrows(
                    RedisException.class,
                    () -> connection.sync().ping(),
                    "commands must time out while Redis is frozen");
            Node node = new Node(provider);
            assertThrows(
                    RedisException.class,
                    () -> node.provider().getUserSession(node.realm(), "outage-1"),
                    "session reads must surface the outage instead of hanging");
        } finally {
            unpauseRedis();
        }
    }

    @Test
    void reconnectsAfterUnpauseWithSessionsIntact() {
        createSession("outage-2", "survivor");

        pauseRedis();
        unpauseRedis();

        awaitRedisUp();

        Node node = new Node(provider);
        UserSessionModel loaded = node.provider().getUserSession(node.realm(), "outage-2");
        assertNotNull(loaded, "session written before the outage must survive a pause");
        assertEquals("survivor", loaded.getNote("marker"));
    }

    @Test
    void reconnectsAfterHardRestartAndDegradesToMisses() {
        createSession("outage-3", "volatile");

        // Crash without persistence: SIGKILL gives Redis no chance to save an RDB (a
        // graceful stop would persist data and hide the outage), and no AOF is configured.
        container.getDockerClient().killContainerCmd(container.getContainerId()).exec();
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();

        awaitRedisUp();

        Node node = new Node(provider);
        assertNull(
                node.provider().getUserSession(node.realm(), "outage-3"),
                "after a data-less restart the session degrades to a miss, not an error");

        // The extension is writable again right away.
        createSession("outage-3b", "rebuilt");
        Node reader = new Node(provider);
        UserSessionModel rebuilt = reader.provider().getUserSession(reader.realm(), "outage-3b");
        assertNotNull(rebuilt);
        assertEquals("rebuilt", rebuilt.getNote("marker"));
    }

    private void createSession(String sessionId, String marker) {
        Node node = new Node(provider);
        UserSessionModel created =
                node.provider()
                        .createUserSession(
                                sessionId, node.realm(), node.user(), "alice", "ip", "form", false,
                                null, null, SessionPersistenceState.PERSISTENT);
        created.setNote("marker", marker);
        node.commit();
        assertEquals(
                1L,
                provider.sync().exists(UserSessionKey.of(TestSessions.REALM_ID, sessionId, false).key()));
    }

    private void pauseRedis() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private void unpauseRedis() {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    private void awaitRedisUp() {
        Awaitility.await("Redis reachable again")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .until(() -> "PONG".equalsIgnoreCase(connection.sync().ping()));
    }

    private static int findFreePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** Redis container bound to a fixed host port so stop/start keeps the same endpoint. */
    private static final class FixedPortRedisContainer extends RedisContainer {
        FixedPortRedisContainer(int hostPort) {
            super(DockerImageName.parse("redis:7.2-alpine"));
            addFixedExposedPort(hostPort, 6379);
        }
    }

    private static final class Node {
        private final KeycloakSession session;
        private final RedisUserSessionProvider provider;

        Node(RedisConnectionProvider connectionProvider) {
            this.session = TestSessions.newSession(connectionProvider);
            this.provider = new RedisUserSessionProvider(session, 100, false);
        }

        RedisUserSessionProvider provider() {
            return provider;
        }

        RealmModel realm() {
            return session.realms().getRealm(TestSessions.REALM_ID);
        }

        UserModel user() {
            return session.users().getUserById(realm(), TestSessions.USER_ID);
        }

        void commit() {
            session.getTransactionManager().commit();
        }
    }
}
