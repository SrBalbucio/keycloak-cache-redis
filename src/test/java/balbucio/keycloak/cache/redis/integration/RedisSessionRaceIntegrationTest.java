package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.userSession.RedisUserSessionProvider;
import balbucio.keycloak.cache.redis.userSession.UserSessionKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionModel.SessionPersistenceState;

/**
 * Race-condition coverage for the optimistic CAS write path: concurrent updates must rebase and
 * retry without lost updates, and concurrent creates of the same session id must converge.
 */
class RedisSessionRaceIntegrationTest extends AbstractRedisIntegrationTest {

    /** Counters registered on the bare composite global registry are no-ops unless a concrete
     *  registry is attached, so attach one for the duration of these tests. */
    @BeforeAll
    static void attachMetricsRegistry() {
        Metrics.globalRegistry.add(new SimpleMeterRegistry());
    }

    @Test
    void concurrentUpdatesRebaseWithoutLostUpdates() {
        String sessionId = "race-1";
        createSession(sessionId, null);

        // Two "nodes" load the same session (both observe version 1).
        Node nodeB = new Node();
        Node nodeC = new Node();
        UserSessionModel loadedB = nodeB.provider().getUserSession(nodeB.realm(), sessionId);
        UserSessionModel loadedC = nodeC.provider().getUserSession(nodeC.realm(), sessionId);
        assertNotNull(loadedB);
        assertNotNull(loadedC);

        double retriesBefore = casRetryCount();

        loadedB.setNote("fromB", "b");
        nodeB.commit();

        // Node C still holds the stale loaded version; commit must hit a CAS conflict,
        // rebase onto node B's write and retry successfully.
        loadedC.setNote("fromC", "c");
        nodeC.commit();

        assertTrue(casRetryCount() > retriesBefore, "expected at least one CAS_RETRY after conflict");

        Node fresh = new Node();
        UserSessionModel merged = fresh.provider().getUserSession(fresh.realm(), sessionId);
        assertNotNull(merged);
        assertEquals("b", merged.getNote("fromB"), "node B update must survive");
        assertEquals("c", merged.getNote("fromC"), "node C update must survive via rebase");

        String version =
                provider().sync().hget(UserSessionKey.of(TestSessions.REALM_ID, sessionId, false).key(),
                        Constants.VERSION_FIELD);
        assertEquals("3", version, "create(1) + update B(2) + rebased update C(3)");
    }

    @Test
    void concurrentCreatesWithSameIdConverge() throws Exception {
        String sessionId = "race-dup";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String login = "node-" + i;
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    Node node = new Node();
                                    node.provider()
                                            .createUserSession(
                                                    sessionId, node.realm(), node.user(), login, "ip",
                                                    "form", false, null, null,
                                                    SessionPersistenceState.PERSISTENT);
                                    node.commit();
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // One create wins (version 1); the loser rebases onto the existing hash and its retry
        // lands as an update (version 2) instead of failing or duplicating the session.
        Node fresh = new Node();
        UserSessionModel session = fresh.provider().getUserSession(fresh.realm(), sessionId);
        assertNotNull(session, "session must exist after concurrent creates");
        String version =
                provider().sync().hget(UserSessionKey.of(TestSessions.REALM_ID, sessionId, false).key(),
                        Constants.VERSION_FIELD);
        assertEquals("2", version, "winner create(1) + rebased loser create-as-update(2)");
        assertEquals(
                1L,
                provider().sync().exists(UserSessionKey.of(TestSessions.REALM_ID, sessionId, false).key()));
    }

    @Test
    void parallelUpdatesOnDistinctFieldsAllSurvive() throws Exception {
        String sessionId = "race-stress";
        createSession(sessionId, null);

        int nodes = 6;
        ExecutorService executor = Executors.newFixedThreadPool(nodes);
        CyclicBarrier barrier = new CyclicBarrier(nodes);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < nodes; i++) {
                String note = "t" + i;
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    updateWithRetry(sessionId, note, 10);
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        Node fresh = new Node();
        UserSessionModel merged = fresh.provider().getUserSession(fresh.realm(), sessionId);
        assertNotNull(merged);
        for (int i = 0; i < nodes; i++) {
            assertEquals("v" + i, merged.getNote("t" + i), "update t" + i + " must not be lost");
        }
    }

    /** Load-modify-commit with application-level retry, as a Keycloak request would retry. */
    private static void updateWithRetry(String sessionId, String note, int maxAttempts) throws Exception {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Node node = new Node();
            try {
                UserSessionModel loaded = node.provider().getUserSession(node.realm(), sessionId);
                assertNotNull(loaded);
                loaded.setNote(note, "v" + note.substring(1));
                node.commit();
                return;
            } catch (IllegalStateException retryable) {
                if (attempt == maxAttempts) {
                    throw retryable;
                }
                Thread.sleep(20L * attempt);
            }
        }
    }

    private void createSession(String sessionId, String note) {
        Node node = new Node();
        UserSessionModel created =
                node.provider()
                        .createUserSession(
                                sessionId, node.realm(), node.user(), "alice", "ip", "form", false,
                                null, null, SessionPersistenceState.PERSISTENT);
        if (note != null) {
            created.setNote(note, "seed");
        }
        node.commit();
    }

    private static double casRetryCount() {
        Counter counter =
                Metrics.globalRegistry
                        .find(RedisMetrics.METRIC_NAME)
                        .tag(RedisMetrics.CACHE_TAG, RedisMetrics.Cache.GENERIC)
                        .tag(RedisMetrics.OPERATION_TAG, RedisMetrics.Op.CAS_RETRY)
                        .counter();
        return counter == null ? 0d : counter.count();
    }

    /** One simulated node: isolated {@link KeycloakSession} + provider over the shared Redis. */
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
