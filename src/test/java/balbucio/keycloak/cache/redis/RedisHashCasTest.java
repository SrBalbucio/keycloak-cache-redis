package balbucio.keycloak.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisMode;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

class RedisHashCasTest {

    private static RedisContainer container;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static String casSha;
    private static RedisConnectionProvider provider;

    @BeforeAll
    static void startRedis() {
        String uri = System.getenv("REDIS_TEST_URI");
        if (uri == null || uri.isBlank()) {
            uri = System.getProperty("redis.test.uri", "");
        }

        if (uri == null || uri.isBlank()) {
            assumeTrue(isDockerAvailable(), "Docker not available for Testcontainers");
            container = new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));
            container.start();
            uri = container.getRedisURI();
        }

        client = RedisClient.create(RedisURI.create(uri));
        connection = client.connect();
        assumeTrue("PONG".equalsIgnoreCase(connection.sync().ping()), "Redis not reachable at " + uri);
        casSha = RedisHashCas.load(connection.sync());
        provider =
                new RedisConnectionProvider() {
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
                        return casSha;
                    }

                    @Override
                    public void close() {}
                };
    }

    @AfterAll
    static void stopRedis() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void flush() {
        connection.sync().flushdb();
        casSha = RedisHashCas.load(connection.sync());
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    void createThenUpdateWithCas() {
        String key = "cas:test:1";
        long expireAt = System.currentTimeMillis() + 60_000;

        long created =
                RedisHashCas.hsetex(
                        provider, key, null, expireAt, Map.of("name", "alice", "role", "admin"), List.of());
        assertEquals(1L, created);
        assertEquals("1", connection.sync().hget(key, "version"));
        assertEquals("alice", connection.sync().hget(key, "name"));

        long updated =
                RedisHashCas.hsetex(
                        provider, key, 1L, expireAt, Map.of("name", "bob"), List.of("role"));
        assertEquals(1L, updated);
        assertEquals("2", connection.sync().hget(key, "version"));
        assertEquals("bob", connection.sync().hget(key, "name"));
        assertEquals(null, connection.sync().hget(key, "role"));
    }

    @Test
    void versionMismatchReturnsZero() {
        String key = "cas:test:2";
        long expireAt = System.currentTimeMillis() + 60_000;

        assertEquals(
                1L,
                RedisHashCas.hsetex(provider, key, null, expireAt, Map.of("x", "1"), List.of()));
        long mismatch =
                RedisHashCas.hsetex(provider, key, 99L, expireAt, Map.of("x", "2"), List.of());
        assertEquals(0L, mismatch);
        assertEquals("1", connection.sync().hget(key, "x"));
        assertEquals("1", connection.sync().hget(key, "version"));
    }

    @Test
    void createConflictReturnsMinusOne() {
        String key = "cas:test:3";
        long expireAt = System.currentTimeMillis() + 60_000;

        assertEquals(
                1L,
                RedisHashCas.hsetex(provider, key, null, expireAt, Map.of("x", "1"), List.of()));
        long conflict =
                RedisHashCas.hsetex(provider, key, null, expireAt, Map.of("x", "2"), List.of());
        assertEquals(-1L, conflict);
    }

    @Test
    void scriptShaIsLoaded() {
        assertNotNull(provider.casScriptSha());
        assertTrue(provider.casScriptSha().length() > 0);
    }
}
