package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.cluster.RedisPubsubClusterProvider;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider.DCNotify;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.models.cache.infinispan.events.ClientAddedEvent;

class RedisPubsubClusterProviderIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String TASK_KEY = "e2e-task";

    @Test
    void publishesAndDeliversEventToListenerOnOtherNode() throws Exception {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider nodeA =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");
            RedisPubsubClusterProvider nodeB =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-b");
            Thread.sleep(300);

            List<ClusterEvent> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            nodeB.registerListener(
                    TASK_KEY,
                    event -> {
                        received.add(event);
                        latch.countDown();
                    });

            ClusterEvent event = ClientAddedEvent.create("client-1", "realm-1");
            nodeA.notify(TASK_KEY, event, true, DCNotify.ALL_DCS);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "listener on node B did not receive the event");
            assertEquals(1, received.size());
            assertEquals(event, received.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void senderIgnoresItsOwnEventsWhenIgnoreSenderIsSet() throws Exception {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider nodeA =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");
            Thread.sleep(300);

            CountDownLatch latch = new CountDownLatch(1);
            nodeA.registerListener(TASK_KEY, event -> latch.countDown());

            nodeA.notify(TASK_KEY, ClientAddedEvent.create("client-2", "realm-1"), true, DCNotify.ALL_DCS);

            assertFalse(latch.await(1, TimeUnit.SECONDS), "own event should have been ignored");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeIfNotExecutedRunsOnlyOnce() {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider provider =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");

            AtomicInteger runs = new AtomicInteger();
            ExecutionResult<String> first =
                    provider.executeIfNotExecuted(
                            TASK_KEY, 30, () -> {
                                runs.incrementAndGet();
                                return "done";
                            });
            ExecutionResult<String> second =
                    provider.executeIfNotExecuted(
                            TASK_KEY, 30, () -> {
                                runs.incrementAndGet();
                                return "done";
                            });

            assertTrue(first.isExecuted());
            assertEquals("done", first.getResult());
            assertFalse(second.isExecuted());
            assertEquals(1, runs.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
