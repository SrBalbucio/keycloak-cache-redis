package balbucio.keycloak.cache.redis.authz.cache;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.authz.AuthorizationCacheConfig;
import balbucio.keycloak.cache.redis.authz.model.CachedResource;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedisAuthorizationCacheMetricsTest {

    private static SimpleMeterRegistry registry;

    private RedisSync sync;
    private RedisAuthorizationCache cache;

    @BeforeAll
    static void bindRegistry() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterAll
    static void unbindRegistry() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    @BeforeEach
    void setUp() {
        sync = mock(RedisSync.class);
        RedisConnectionProvider connection = mock(RedisConnectionProvider.class);
        when(connection.sync()).thenReturn(sync);
        cache =
                new RedisAuthorizationCache(
                        connection, new ObjectMapper(), AuthorizationCacheConfig.load());
    }

    @Test
    void recordsMissWhenKeyAbsent() {
        when(sync.get(anyString())).thenReturn(null);
        double before = outcomeCount(RedisMetrics.Op.MISS);

        assertNull(cache.get("kc:missing", 0L, CachedResource.class));

        assertTrue(outcomeCount(RedisMetrics.Op.MISS) > before, "expected MISS counter to increase");
    }

    @Test
    void recordsErrorWhenRedisThrows() {
        when(sync.get(anyString())).thenThrow(new RuntimeException("boom"));
        double before = outcomeCount(RedisMetrics.Op.ERROR);

        assertNull(cache.get("kc:err", 0L, CachedResource.class));

        assertTrue(outcomeCount(RedisMetrics.Op.ERROR) > before, "expected ERROR counter to increase");
    }

    private static double outcomeCount(String op) {
        Optional<Meter> meter =
                Metrics.globalRegistry.getMeters().stream()
                        .filter(m -> RedisMetrics.METRIC_NAME.equals(m.getId().getName()))
                        .filter(m -> RedisMetrics.Cache.AUTHZ.equals(m.getId().getTag(RedisMetrics.CACHE_TAG)))
                        .filter(m -> op.equals(m.getId().getTag(RedisMetrics.OPERATION_TAG)))
                        .findFirst();
        return meter.map(m -> m.measure().iterator().next().getValue()).orElse(0.0);
    }
}
