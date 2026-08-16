package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.cluster.RedisPubsubClusterProvider;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.userSession.RedisUserSessionProvider;
import balbucio.keycloak.cache.redis.userSession.UserSessionKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionModel.SessionPersistenceState;
import org.keycloak.models.cache.infinispan.events.ClientAddedEvent;

/**
 * Schema-evolution compatibility for the Redis hash layout: entities written by an older or newer
 * provider version (missing/extra fields) must stay readable and updatable, and malformed cluster
 * events must not take a subscriber node down.
 */
class RedisSerializationCompatibilityTest extends AbstractRedisIntegrationTest {

    @Test
    void sessionWithMissingOptionalFieldsLoads() {
        String key = UserSessionKey.of(TestSessions.REALM_ID, "compat-missing", false).key();
        provider()
                .sync()
                .hset(
                        key,
                        Map.of(
                                "version", "1",
                                "id", "compat-missing",
                                "realmId", TestSessions.REALM_ID,
                                "userId", TestSessions.USER_ID,
                                "loginUsername", "alice",
                                "started", "1700000000",
                                "lastSessionRefresh", "1700000000",
                                "offline", "false",
                                "rememberMe", "false",
                                "persistenceState", "PERSISTENT"));

        Node node = new Node();
        UserSessionModel loaded = node.provider().getUserSession(node.realm(), "compat-missing");

        assertNotNull(loaded, "sparse hash must stay readable");
        assertEquals("alice", loaded.getLoginUsername());
        assertNull(loaded.getIpAddress(), "missing field must read as null");
        assertNull(loaded.getBrokerSessionId());
        assertNull(loaded.getNote("anything"));
        assertEquals(1700000000, loaded.getStarted());
    }

    @Test
    void unknownFieldsSurviveLoadAndUpdate() {
        Node creator = new Node();
        creator.provider()
                .createUserSession(
                        "compat-extra", creator.realm(), creator.user(), "alice", "ip", "form",
                        false, null, null, SessionPersistenceState.PERSISTENT);
        creator.commit();

        String key = UserSessionKey.of(TestSessions.REALM_ID, "compat-extra", false).key();
        provider().sync().hset(key, "futureField", "abc");
        provider().sync().hset(key, "zeta.index", "9");

        Node updater = new Node();
        UserSessionModel loaded = updater.provider().getUserSession(updater.realm(), "compat-extra");
        assertNotNull(loaded);
        loaded.setNote("touched", "yes");
        updater.commit();

        assertEquals("abc", provider().sync().hget(key, "futureField"),
                "unknown field must survive an unrelated update");
        assertEquals("9", provider().sync().hget(key, "zeta.index"));
        assertEquals("yes",
                provider().sync().hget(key, balbucio.keycloak.cache.redis.common.Constants.NOTE_PREFIX + "touched"));
    }

    @Test
    void missingVersionFieldIsAdoptedOnFirstCommit() {
        String key = UserSessionKey.of(TestSessions.REALM_ID, "compat-noversion", false).key();
        provider()
                .sync()
                .hset(
                        key,
                        Map.of(
                                "id", "compat-noversion",
                                "realmId", TestSessions.REALM_ID,
                                "userId", TestSessions.USER_ID,
                                "started", "1700000000",
                                "lastSessionRefresh", "1700000000",
                                "offline", "false"));

        Node node = new Node();
        UserSessionModel loaded = node.provider().getUserSession(node.realm(), "compat-noversion");
        assertNotNull(loaded);
        loaded.setNote("adopted", "yes");
        node.commit();

        assertEquals("1", provider().sync().hget(key, "version"),
                "version-less hash must be adopted as version 1 on first write");
    }

    @Test
    void corruptedVersionFieldFailsRead() {
        String key = UserSessionKey.of(TestSessions.REALM_ID, "compat-corrupt", false).key();
        provider()
                .sync()
                .hset(
                        key,
                        Map.of(
                                "version", "not-a-number",
                                "id", "compat-corrupt",
                                "realmId", TestSessions.REALM_ID,
                                "userId", TestSessions.USER_ID,
                                "started", "1700000000",
                                "lastSessionRefresh", "1700000000",
                                "offline", "false"));

        Node node = new Node();
        // CHARACTERIZED HAZARD: one corrupted field breaks the read for that session instead of
        // degrading to a miss.
        assertThrows(
                NumberFormatException.class,
                () -> node.provider().getUserSession(node.realm(), "compat-corrupt"));
    }

    @Test
    void malformedClusterEventDoesNotTakeSubscriberDown() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            RedisPubsubClusterProvider node =
                    new RedisPubsubClusterProvider(
                            provider().sync(), provider().connectPubSub(), 100, executor, "node-compat");
            Thread.sleep(300);

            List<ClusterEvent> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            node.registerListener("compat-task", event -> {
                received.add(event);
                latch.countDown();
            });

            // Unknown extra field inside the event payload: rejected by the serializer.
            String malformed =
                    "{\"eventKey\":\"compat-task\",\"events\":[{\"@class\":"
                            + "\"org.keycloak.models.cache.infinispan.events.ClientAddedEvent\","
                            + "\"id\":\"c1\",\"realmId\":\"r1\",\"futureFlag\":true}],"
                            + "\"ignoreSender\":false,\"dcNotify\":\"ALL_DCS\",\"senderId\":\"n1\"}";
            provider().sync().publish(RedisKeySpace.key("cluster:events"), malformed);

            Thread.sleep(500);
            assertTrue(received.isEmpty(), "malformed event must be dropped, not delivered");

            // The node must keep consuming: a valid event right after is delivered normally.
            ClusterEvent valid = ClientAddedEvent.create("c2", "r1");
            node.notify("compat-task", valid, false, org.keycloak.cluster.ClusterProvider.DCNotify.ALL_DCS);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "valid event after malformed one must arrive");
            assertEquals(1, received.size());
            assertEquals(valid, received.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class Node {
        private final KeycloakSession session = TestSessions.newSession(IntegrationRedis.provider());
        private final RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

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
