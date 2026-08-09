package balbucio.keycloak.cache.redis.authz.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import balbucio.keycloak.cache.redis.authz.AuthorizationCacheConfig;
import balbucio.keycloak.cache.redis.connection.LettuceRedisAsync;
import balbucio.keycloak.cache.redis.connection.LettuceRedisSync;
import balbucio.keycloak.cache.redis.connection.RedisAsync;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisMode;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

class RedisAuthorizationCacheIntegrationTest {

    private static RedisContainer container;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisConnectionProvider provider;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        RedisSync sync = LettuceRedisSync.of(connection.sync());
        assumeTrue("PONG".equalsIgnoreCase(sync.ping()), "Redis not reachable at " + uri);

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
    void flushRedis() {
        connection.sync().flushdb();
    }

    @AfterEach
    void clearProps() {
        System.clearProperty(AuthorizationCacheConfig.PROP_LRU_ENABLED);
        System.clearProperty(AuthorizationCacheConfig.PROP_LRU_MAX_SIZE);
        System.clearProperty(AuthorizationCacheConfig.PROP_LRU_TTL);
    }

    @Test
    void putAndGetRoundTrip() {
        AuthorizationCacheConfig config = AuthorizationCacheConfig.load();
        RedisAuthorizationCache cache = new RedisAuthorizationCache(provider, objectMapper, config);

        SamplePayload payload = new SamplePayload();
        payload.id = "r1";
        payload.name = "resource-one";

        cache.put("authz:test:1", 0L, payload, 60L);

        SamplePayload loaded = cache.get("authz:test:1", 0L, SamplePayload.class);
        assertNotNull(loaded);
        assertEquals("r1", loaded.id);
        assertEquals("resource-one", loaded.name);
    }

    @Test
    void invalidateMakesOlderGenerationStale() {
        AuthorizationCacheConfig config = AuthorizationCacheConfig.load();
        RedisAuthorizationCache cache = new RedisAuthorizationCache(provider, objectMapper, config);

        String rsId = "rs-1";
        long gen0 = cache.currentGeneration(rsId);
        assertEquals(0L, gen0);

        SamplePayload payload = new SamplePayload();
        payload.id = "r2";
        cache.put("authz:test:2", gen0, payload, 60L);

        cache.invalidate(rsId);
        long gen1 = cache.currentGeneration(rsId);
        assertTrue(gen1 > gen0);

        SamplePayload stale = cache.get("authz:test:2", gen1, SamplePayload.class);
        assertNull(stale);
    }

    @Test
    void localLruServesHotEntryAndClearsOnInvalidate() {
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_ENABLED, "true");
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_MAX_SIZE, "32");
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_TTL, "30");

        AuthorizationCacheConfig config = AuthorizationCacheConfig.load();
        LocalAuthorizationCache cache =
                new LocalAuthorizationCache(
                        provider,
                        objectMapper,
                        config,
                        LocalAuthorizationCache.createSharedLru(config.getLruMaxSize()));

        String key = "authz:test:lru";
        String rsId = "rs-lru";
        long gen0 = cache.currentGeneration(rsId);

        SamplePayload payload = new SamplePayload();
        payload.id = "r3";
        payload.name = "local-lru";
        cache.put(key, gen0, payload, 60L);

        // Remove from Redis to prove the local cache can still serve the hot entry.
        connection.sync().del(key);

        SamplePayload fromLocal = cache.get(key, gen0, SamplePayload.class);
        assertNotNull(fromLocal);
        assertEquals("local-lru", fromLocal.name);

        // Invalidate clears local LRU and bumps generation.
        cache.invalidate(rsId);
        long gen1 = cache.currentGeneration(rsId);

        SamplePayload afterInvalidate = cache.get(key, gen1, SamplePayload.class);
        assertNull(afterInvalidate);
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    static class SamplePayload {
        public String id;
        public String name;
    }
}
