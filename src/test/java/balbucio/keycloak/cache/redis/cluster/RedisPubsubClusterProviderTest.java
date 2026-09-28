package balbucio.keycloak.cache.redis.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import io.lettuce.core.SetArgs;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fase 1.4 (sem Docker): o waiter async desiste antes do timeout cheio quando o lock some sem
 * conclusão, e continua esperando o teto enquanto o lock existir.
 */
class RedisPubsubClusterProviderTest {

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
    void waiterExitsEarlyWhenLockVanishesWithoutCompletion() throws Exception {
        RedisSync sync = mock(RedisSync.class);
        when(sync.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);
        when(sync.exists(anyString())).thenReturn(0L);
        ExecutorService executor = Executors.newCachedThreadPool();
        RedisPubsubClusterProvider provider = provider(sync, executor);
        try {
            long start = System.nanoTime();
            Future<Boolean> waiter = provider.executeIfNotExecutedAsync("ghost", 30, () -> "never");
            assertFalse(
                    waiter.get(15, TimeUnit.SECONDS),
                    "vanished lock without completion must resolve as not-executed");
            long elapsedSecs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertTrue(
                    elapsedSecs < 15,
                    "waiter must give up well before the 30s ceiling, took " + elapsedSecs + "s");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void waiterWaitsFullCeilingWhileLockIsHeld() throws Exception {
        RedisSync sync = mock(RedisSync.class);
        when(sync.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);
        when(sync.exists(anyString())).thenReturn(1L);
        ExecutorService executor = Executors.newCachedThreadPool();
        RedisPubsubClusterProvider provider = provider(sync, executor);
        try {
            long start = System.nanoTime();
            Future<Boolean> waiter = provider.executeIfNotExecutedAsync("held", 3, () -> "never");
            assertFalse(waiter.get(10, TimeUnit.SECONDS));
            long elapsedSecs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            assertTrue(
                    elapsedSecs >= 3,
                    "waiter must respect the full ceiling while the lock exists, took "
                            + elapsedSecs
                            + "s");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clusterReconnectListenerOnlyObserves() {
        RedisSync sync = mock(RedisSync.class);
        ExecutorService executor = Executors.newCachedThreadPool();
        RedisPubsubClusterProvider provider = provider(sync, executor);
        try {
            double reconnectedBefore = clusterEventCount("cluster", RedisMetrics.ClusterEvent.RECONNECTED);
            double clearedBefore = clusterEventCount("cluster", RedisMetrics.ClusterEvent.RESYNC_CLEARED);

            provider.reconnectListener.onRedisConnected(null, null);
            assertTrue(
                    clusterEventCount("cluster", RedisMetrics.ClusterEvent.RECONNECTED) == reconnectedBefore,
                    "first connect must be ignored");

            provider.reconnectListener.onRedisConnected(null, null);
            assertTrue(
                    clusterEventCount("cluster", RedisMetrics.ClusterEvent.RECONNECTED) > reconnectedBefore,
                    "reconnect must be recorded");
            assertTrue(
                    clusterEventCount("cluster", RedisMetrics.ClusterEvent.RESYNC_CLEARED) == clearedBefore,
                    "cluster channel has no local L1 to clear");
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisPubsubClusterProvider provider(RedisSync sync, ExecutorService executor) {
        StatefulRedisPubSubConnection<String, String> subscriber =
                mock(StatefulRedisPubSubConnection.class);
        RedisPubSubCommands<String, String> commands = mock(RedisPubSubCommands.class);
        when(subscriber.sync()).thenReturn(commands);
        return new RedisPubsubClusterProvider(sync, subscriber, 100, executor, "node-test");
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
