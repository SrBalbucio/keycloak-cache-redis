package balbucio.keycloak.cache.redis.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.integration.AbstractRedisIntegrationTest;
import balbucio.keycloak.cache.redis.integration.IntegrationRedis;
import balbucio.keycloak.cache.redis.integration.TestSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;

/**
 * Public {@code redisCache} SPI, exercised against real Redis: namespaces, TTL, JSON,
 * cross-node L1 invalidation, validation and fail-open behavior.
 */
class RedisCacheApiIntegrationTest extends AbstractRedisIntegrationTest {

    public static class Widget {
        public String name;
        public int size;

        public Widget() {}

        public Widget(String name, int size) {
            this.name = name;
            this.size = size;
        }
    }

    @Test
    void stringAndPojoRoundTripWithTypeGuard() {
        DefaultRedisCacheProviderFactory factory = newFactory();
        try {
            RedisCache cache = providerFor(factory).getCache("shop");

            cache.put("greeting", "hello", 300);
            assertEquals("hello", cache.get("greeting", String.class));

            cache.put("widget", new Widget("gear", 7), 300);
            Widget back = cache.get("widget", Widget.class);
            assertEquals("gear", back.name);
            assertEquals(7, back.size);

            // Type mismatch is a miss, never a ClassCastException.
            assertNull(cache.get("greeting", Widget.class));
            assertNull(cache.get("widget", String.class));
        } finally {
            factory.close();
        }
    }

    @Test
    void nullPutBehavesAsRemove() {
        DefaultRedisCacheProviderFactory factory = newFactory();
        try {
            RedisCache cache = providerFor(factory).getCache("shop");
            cache.put("k", "v", 300);
            cache.put("k", null, 300);
            assertNull(cache.get("k", String.class));
        } finally {
            factory.close();
        }
    }

    @Test
    void ttlExpiresEntry() {
        DefaultRedisCacheProviderFactory factory = newFactory();
        try {
            RedisCache cache = providerFor(factory)
                    .getCache("ephemeral", RedisCacheConfig.defaults().withLruTtlSeconds(1));
            cache.put("k", "v", 1);
            assertEquals("v", cache.get("k", String.class));

            Awaitility.await("TTL entry must expire")
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertNull(cache.get("k", String.class)));
        } finally {
            factory.close();
        }
    }

    @Test
    void getOrLoadCachesLoaderResult() throws Exception {
        DefaultRedisCacheProviderFactory factory = newFactory();
        try {
            RedisCache cache = providerFor(factory).getCache("loader");
            AtomicInteger calls = new AtomicInteger();

            String first = cache.getOrLoad("k", String.class, 300, () -> {
                calls.incrementAndGet();
                return "computed";
            });
            String second = cache.getOrLoad("k", String.class, 300, () -> {
                calls.incrementAndGet();
                return "recomputed";
            });

            assertEquals("computed", first);
            assertEquals("computed", second);
            assertEquals(1, calls.get(), "loader must run once while cached");

            assertNull(cache.getOrLoad("missing", String.class, 300, () -> null));
            assertNull(cache.get("missing", String.class), "null loader results are not cached");
        } finally {
            factory.close();
        }
    }

    @Test
    void putOnOneNodeInvalidatesOtherNodeLru() {
        DefaultRedisCacheProviderFactory factoryA = newFactory();
        DefaultRedisCacheProviderFactory factoryB = newFactory();
        try {
            RedisCache cacheA = providerFor(factoryA).getCache("shared");
            RedisCache cacheB = providerFor(factoryB).getCache("shared");

            cacheA.put("k", "v1", 300);
            assertEquals("v1", cacheB.get("k", String.class), "B loads from L2");
            assertEquals("v1", cacheB.get("k", String.class), "B serves from L1 now");

            cacheA.put("k", "v2", 300);

            Awaitility.await("B must see the invalidation broadcast")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertEquals("v2", cacheB.get("k", String.class)));
        } finally {
            factoryA.close();
            factoryB.close();
        }
    }

    @Test
    void removeOnOneNodeClearsOtherNode() {
        DefaultRedisCacheProviderFactory factoryA = newFactory();
        DefaultRedisCacheProviderFactory factoryB = newFactory();
        try {
            RedisCache cacheA = providerFor(factoryA).getCache("shared");
            RedisCache cacheB = providerFor(factoryB).getCache("shared");

            cacheA.put("k", "v1", 300);
            assertEquals("v1", cacheB.get("k", String.class));

            cacheA.remove("k");

            Awaitility.await("B must observe the removal")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertNull(cacheB.get("k", String.class)));
        } finally {
            factoryA.close();
            factoryB.close();
        }
    }

    @Test
    void namespacesAreIsolated() {
        DefaultRedisCacheProviderFactory factory = newFactory();
        try {
            RedisCacheProvider provider = providerFor(factory);
            RedisCache ns1 = provider.getCache("ns1");
            RedisCache ns2 = provider.getCache("ns2");

            ns1.put("k", "a", 300);
            assertNull(ns2.get("k", String.class));

            ns2.put("k", "b", 300);
            ns1.remove("k");
            assertEquals("b", ns2.get("k", String.class), "remove in ns1 must not touch ns2");
        } finally {
            factory.close();
        }
    }

    @Test
    void validationRejectsBadInput() {
        DefaultRedisCacheProviderFactory factory = newFactory();
        try {
            RedisCacheProvider provider = providerFor(factory);
            assertThrows(IllegalArgumentException.class, () -> provider.getCache(""));
            assertThrows(IllegalArgumentException.class, () -> provider.getCache("UPPER"));
            assertThrows(IllegalArgumentException.class, () -> provider.getCache("has space"));

            RedisCache cache = provider.getCache("ok");
            assertThrows(IllegalArgumentException.class, () -> cache.get("", String.class));
            assertThrows(IllegalArgumentException.class, () -> cache.get("a*b", String.class));
            assertThrows(IllegalArgumentException.class, () -> cache.put("", "v", 60));
            assertThrows(IllegalArgumentException.class, () -> cache.get("k", null));
        } finally {
            factory.close();
        }
    }

    @Test
    void failOpenWhenRedisIsDown() throws Exception {
        RedisConnectionProvider dead = mock(RedisConnectionProvider.class);
        when(dead.sync()).thenThrow(new RuntimeException("redis down"));
        DefaultRedisCache cache = new DefaultRedisCache(
                "ns", RedisCacheConfig.defaults().withLruEnabled(false), dead, new ObjectMapper(), null);

        assertNull(cache.get("k", String.class));
        assertDoesNotThrow(() -> cache.put("k", "v", 60));
        assertDoesNotThrow(() -> cache.remove("k"));
        assertDoesNotThrow(cache::clear);
        // Loader result is still returned (just not cached anywhere).
        assertEquals("loaded", cache.getOrLoad("k", String.class, 60, () -> "loaded"));
    }

    private static DefaultRedisCacheProviderFactory newFactory() {
        DefaultRedisCacheProviderFactory factory = new DefaultRedisCacheProviderFactory();
        factory.init(mock(Config.Scope.class));
        return factory;
    }

    private static RedisCacheProvider providerFor(DefaultRedisCacheProviderFactory factory) {
        KeycloakSession session = TestSessions.newSession(IntegrationRedis.provider());
        return factory.create(session);
    }
}
