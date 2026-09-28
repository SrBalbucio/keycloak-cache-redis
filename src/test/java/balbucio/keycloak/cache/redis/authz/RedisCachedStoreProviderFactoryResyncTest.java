package balbucio.keycloak.cache.redis.authz;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.integration.AbstractRedisIntegrationTest;
import balbucio.keycloak.cache.redis.integration.IntegrationRedis;
import balbucio.keycloak.cache.redis.integration.TestSessions;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;

/**
 * Fase 1.2: a factory do LRU local de authz registra o listener de reconnect, e dispará-lo
 * reconcilia (métrica {@code resync_cleared} após a ação).
 */
class RedisCachedStoreProviderFactoryResyncTest extends AbstractRedisIntegrationTest {

    private static SimpleMeterRegistry registry;

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

    @Test
    void reconnectListenerReconcilesSharedLru() {
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_ENABLED, "true");
        RedisCachedStoreProviderFactory factory = new RedisCachedStoreProviderFactory();
        try {
            factory.init(mock(Config.Scope.class));
            KeycloakSession session = TestSessions.newSession(IntegrationRedis.provider());
            factory.create(session);

            assertNotNull(
                    factory.reconnectListener, "LRU subscriber wiring must register a reconnect listener");

            double reconnectedBefore = clusterEventCount("authz-lru", RedisMetrics.ClusterEvent.RECONNECTED);
            double clearedBefore = clusterEventCount("authz-lru", RedisMetrics.ClusterEvent.RESYNC_CLEARED);

            factory.reconnectListener.onRedisConnected(null, null);
            assertTrue(
                    clusterEventCount("authz-lru", RedisMetrics.ClusterEvent.RECONNECTED) == reconnectedBefore,
                    "first connect must be ignored");

            factory.reconnectListener.onRedisConnected(null, null);
            assertTrue(
                    clusterEventCount("authz-lru", RedisMetrics.ClusterEvent.RECONNECTED) > reconnectedBefore,
                    "reconnect must be recorded");
            assertTrue(
                    clusterEventCount("authz-lru", RedisMetrics.ClusterEvent.RESYNC_CLEARED) > clearedBefore,
                    "reconnect must clear the shared LRU");
        } finally {
            System.clearProperty(AuthorizationCacheConfig.PROP_LRU_ENABLED);
            factory.close();
        }
        assertNull(factory.reconnectListener, "close must release the listener");
    }

    private static double clusterEventCount(String eventKey, String outcome) {
        Optional<Meter> meter =
                Metrics.globalRegistry.getMeters().stream()
                        .filter(m -> RedisMetrics.CLUSTER_EVENTS_METRIC.equals(m.getId().getName()))
                        .filter(m -> eventKey.equals(m.getId().getTag("eventKey")))
                        .filter(m -> outcome.equals(m.getId().getTag("outcome")))
                        .findFirst();
        return meter.map(m -> m.measure().iterator().next().getValue()).orElse(0.0);
    }
}
