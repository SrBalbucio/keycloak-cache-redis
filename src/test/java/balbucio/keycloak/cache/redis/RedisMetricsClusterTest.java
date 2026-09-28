package balbucio.keycloak.cache.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fase 0: a observabilidade do cluster bus nunca pode quebrar o request path e deve segmentar por
 * eventKey para confrontar PUBLISH vs DELIVER.
 */
class RedisMetricsClusterTest {

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
    void clusterEventsAreCountedByEventKeyAndOutcome() {
        double before =
                clusterEventCount("REALM_INVALIDATION_EVENTS", RedisMetrics.ClusterEvent.SENT);

        assertDoesNotThrow(
                () ->
                        RedisMetrics.recordClusterEvent(
                                "REALM_INVALIDATION_EVENTS", RedisMetrics.ClusterEvent.SENT));

        assertTrue(
                clusterEventCount("REALM_INVALIDATION_EVENTS", RedisMetrics.ClusterEvent.SENT) > before,
                "expected cluster SENT counter to increase for the eventKey");
    }

    @Test
    void nullEventKeyFallsBackToUnknown() {
        double before = clusterEventCount("unknown", RedisMetrics.ClusterEvent.DELIVERED);

        assertDoesNotThrow(() -> RedisMetrics.recordClusterEvent(null, RedisMetrics.ClusterEvent.DELIVERED));

        assertTrue(
                clusterEventCount("unknown", RedisMetrics.ClusterEvent.DELIVERED) > before,
                "expected null eventKey to be counted as unknown");
    }

    @Test
    void clusterLagAndTaskWaitsAreRecorded() {
        assertDoesNotThrow(() -> RedisMetrics.recordClusterLag("k", System.currentTimeMillis()));
        assertDoesNotThrow(() -> RedisMetrics.recordClusterLag("k", 0L));
        assertDoesNotThrow(() -> RedisMetrics.recordClusterLag("k", -1L));
        assertDoesNotThrow(
                () -> RedisMetrics.recordClusterTask("t", RedisMetrics.ClusterTask.FINISHED, 12L));
        assertDoesNotThrow(
                () -> RedisMetrics.recordClusterTask("t", RedisMetrics.ClusterTask.TIMEOUT, 30_000L));
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
