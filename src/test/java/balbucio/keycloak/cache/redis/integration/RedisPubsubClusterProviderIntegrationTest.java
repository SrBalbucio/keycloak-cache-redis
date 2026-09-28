package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.cluster.RedisPubsubClusterProvider;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider.DCNotify;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.cache.infinispan.events.AuthenticationSessionAuthNoteUpdateEvent;
import org.keycloak.models.cache.infinispan.events.CacheKeyInvalidatedEvent;
import org.keycloak.models.cache.infinispan.events.ClientAddedEvent;
import org.keycloak.models.cache.infinispan.events.ClientRemovedEvent;
import org.keycloak.models.cache.infinispan.events.ClientScopeAddedEvent;
import org.keycloak.models.cache.infinispan.events.ClientScopeRemovedEvent;
import org.keycloak.models.cache.infinispan.events.ClientUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.GroupAddedEvent;
import org.keycloak.models.cache.infinispan.events.GroupMovedEvent;
import org.keycloak.models.cache.infinispan.events.GroupRemovedEvent;
import org.keycloak.models.cache.infinispan.events.GroupUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.RealmRemovedEvent;
import org.keycloak.models.cache.infinispan.events.RealmUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.RoleAddedEvent;
import org.keycloak.models.cache.infinispan.events.RoleRemovedEvent;
import org.keycloak.models.cache.infinispan.events.RoleUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserCacheRealmInvalidationEvent;
import org.keycloak.models.cache.infinispan.events.UserConsentsUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserFederationLinkRemovedEvent;
import org.keycloak.models.cache.infinispan.events.UserFederationLinkUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserFullInvalidationEvent;
import org.keycloak.models.cache.infinispan.events.UserUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserVerifiableCredentialsUpdatedEvent;

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
    void executeIfNotExecutedRunsOnlyOnceWhileLockHeld() throws Exception {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider cluster =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");

            AtomicInteger runs = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            var firstFuture =
                    executor.submit(
                            () ->
                                    cluster.executeIfNotExecuted(
                                            TASK_KEY,
                                            30,
                                            () -> {
                                                started.countDown();
                                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                                runs.incrementAndGet();
                                                return "done";
                                            }));

            assertTrue(started.await(5, TimeUnit.SECONDS));
            ExecutionResult<String> second =
                    cluster.executeIfNotExecuted(
                            TASK_KEY,
                            30,
                            () -> {
                                runs.incrementAndGet();
                                return "done";
                            });
            assertFalse(second.isExecuted());

            release.countDown();
            ExecutionResult<String> first = firstFuture.get(5, TimeUnit.SECONDS);
            assertTrue(first.isExecuted());
            assertEquals("done", first.getResult());
            assertEquals(1, runs.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeIfNotExecutedAsyncCompletesWinnerWithoutFullTimeout() throws Exception {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider cluster =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");
            Thread.sleep(200);

            long started = System.nanoTime();
            Future<Boolean> future =
                    cluster.executeIfNotExecutedAsync("async-winner", 30, () -> "ok");
            assertTrue(future.get(5, TimeUnit.SECONDS));
            assertTrue(
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10,
                    "winner should not wait for the full lock timeout");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeIfNotExecutedAsyncWaiterCompletesAfterOtherNodeUnlocks() throws Exception {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider nodeA =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");
            RedisPubsubClusterProvider nodeB =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-b");
            Thread.sleep(300);

            CountDownLatch holderStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();

            Future<Boolean> holder =
                    nodeA.executeIfNotExecutedAsync(
                            "async-cross",
                            30,
                            () -> {
                                holderStarted.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                runs.incrementAndGet();
                                return "done";
                            });

            assertTrue(holderStarted.await(5, TimeUnit.SECONDS));

            Future<Boolean> waiter =
                    nodeB.executeIfNotExecutedAsync(
                            "async-cross",
                            30,
                            () -> {
                                runs.incrementAndGet();
                                return "should-not-run";
                            });

            Thread.sleep(200);
            release.countDown();

            assertTrue(holder.get(5, TimeUnit.SECONDS));
            assertTrue(waiter.get(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allSupportedEventTypesAreDeliveredInterNode() throws Exception {
        RedisConnectionProvider conn = provider();
        RedisSync publisher = conn.sync();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider nodeA =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-a");
            RedisPubsubClusterProvider nodeB =
                    new RedisPubsubClusterProvider(publisher, conn.connectPubSub(), 100, executor, "node-b");
            Thread.sleep(300);

            List<ClusterEvent> sent = allSupportedEvents();
            List<ClusterEvent> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(sent.size());
            nodeB.registerListener(
                    TASK_KEY,
                    event -> {
                        received.add(event);
                        latch.countDown();
                    });

            for (ClusterEvent event : sent) {
                nodeA.notify(TASK_KEY, event, true, DCNotify.ALL_DCS);
            }

            assertTrue(
                    latch.await(15, TimeUnit.SECONDS),
                    "not all event types arrived, got " + received.size() + " of " + sent.size());
            assertEquals(sent, received);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Fase 1.3: um exemplar de cada tipo coberto pelo {@code ClusterEventSerializer}. Se um novo
     * evento for adicionado ao serializer sem chegar aqui, a cobertura de transporte fica muda —
     * manter em sincronia com {@code ClusterEventSerializerTest} (round-trip) e {@code
     * ClusterEventMixin} (allowlist).
     */
    private static List<ClusterEvent> allSupportedEvents() throws Exception {
        Map<String, String> notes = new HashMap<>();
        notes.put("k1", "v1");
        Map<String, String> roles = new HashMap<>();
        roles.put("role-1", "role-name-1");

        List<ClusterEvent> events = new ArrayList<>();
        events.add(AuthenticationSessionAuthNoteUpdateEvent.create("auth-1", "tab-1", notes));
        events.add(new CacheKeyInvalidatedEvent("cache-key-1"));
        events.add(ClientAddedEvent.create("client-uuid-1", "realm-1"));
        events.add(clientRemovedEvent(roles));
        events.add(ClientScopeAddedEvent.create("scope-1", "realm-1"));
        events.add(ClientScopeRemovedEvent.create("scope-2", "realm-1"));
        events.add(ClientUpdatedEvent.create("client-uuid-2", "client-id-2", "realm-1"));
        events.add(GroupAddedEvent.create("group-1", "parent-1", "realm-1"));
        events.add(groupMovedEvent());
        events.add(new GroupRemovedEvent("group-3", "realm-1", "parent-3"));
        events.add(GroupUpdatedEvent.create("group-4"));
        events.add(RealmRemovedEvent.create("realm-1", "realm-name-1"));
        events.add(RealmUpdatedEvent.create("realm-2", "realm-name-2"));
        events.add(RoleAddedEvent.create("role-1", "container-1", "role-name-1"));
        events.add(RoleRemovedEvent.create("role-2", "role-name-2", "container-2"));
        events.add(RoleUpdatedEvent.create("role-3", "role-name-3", "container-3"));
        events.add(UserCacheRealmInvalidationEvent.create("realm-3"));
        events.add(UserConsentsUpdatedEvent.create("user-1"));
        events.add(UserFederationLinkUpdatedEvent.create("user-2"));
        events.add(
                UserFederationLinkRemovedEvent.create(
                        "user-3", "realm-4", new FederatedIdentityModel("provider-1", "social-1", "user-3")));
        events.add(
                UserFullInvalidationEvent.create(
                        "user-4",
                        "alice",
                        "alice@example.com",
                        "realm-5",
                        true,
                        Stream.of(new FederatedIdentityModel("github", "12345", "alice"))));
        events.add(UserUpdatedEvent.create("user-5", "bob", "bob@example.com", "realm-6"));
        events.add(UserVerifiableCredentialsUpdatedEvent.create("user-vc-1"));
        return events;
    }

    private static ClientRemovedEvent clientRemovedEvent(Map<String, String> roles) throws Exception {
        Constructor<ClientRemovedEvent> ctor =
                ClientRemovedEvent.class.getDeclaredConstructor(
                        String.class, String.class, String.class, Map.class);
        ctor.setAccessible(true);
        return ctor.newInstance("client-uuid-3", "realm-1", "client-id-3", roles);
    }

    private static GroupMovedEvent groupMovedEvent() throws Exception {
        Constructor<GroupMovedEvent> ctor =
                GroupMovedEvent.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("group-5", "new-parent-5", null, "realm-1");
    }
}
