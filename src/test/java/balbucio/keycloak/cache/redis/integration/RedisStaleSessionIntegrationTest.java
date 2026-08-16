package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import balbucio.keycloak.cache.redis.entity.RedisEntityIndexCache;
import balbucio.keycloak.cache.redis.userSession.RedisUserSessionProvider;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionModel.SessionPersistenceState;

/**
 * Stale-read coverage. Sessions are read-through (no L1 session cache), so cross-transaction
 * reads must always see the latest committed state; the L1 entity index cache relies on pub/sub
 * invalidation to stay coherent across nodes.
 */
class RedisStaleSessionIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void committedUpdateIsVisibleToNewReadersImmediately() {
        String sessionId = "stale-1";
        Node nodeA = new Node();
        UserSessionModel created =
                nodeA.provider()
                        .createUserSession(
                                sessionId, nodeA.realm(), nodeA.user(), "alice", "ip", "form", false,
                                null, null, SessionPersistenceState.PERSISTENT);
        created.setNote("theme", "dark");
        nodeA.commit();

        Node nodeB = new Node();
        UserSessionModel loadedB = nodeB.provider().getUserSession(nodeB.realm(), sessionId);
        assertNotNull(loadedB);
        assertEquals("dark", loadedB.getNote("theme"));

        Node nodeC = new Node();
        UserSessionModel loadedC = nodeC.provider().getUserSession(nodeC.realm(), sessionId);
        loadedC.setNote("theme", "light");
        nodeC.commit();

        // Within its own transaction node B keeps the snapshot it loaded (expected unit-of-work
        // semantics), but any NEW reader must see the committed update immediately.
        assertEquals("dark", loadedB.getNote("theme"), "in-transaction snapshot is immutable");

        Node nodeD = new Node();
        UserSessionModel fresh = nodeD.provider().getUserSession(nodeD.realm(), sessionId);
        assertNotNull(fresh);
        assertEquals("light", fresh.getNote("theme"), "new reader must not see stale data");
    }

    @Test
    void entityIndexInvalidationClearsOtherNodeLocalCacheViaPubSub() {
        RedisEntityIndexCache cacheA =
                new RedisEntityIndexCache(provider(), 300, new ConcurrentHashMap<>());
        StatefulRedisPubSubConnection<String, String> subscriberA = provider().connectPubSub();
        try {
            subscriberA.addListener(
                    new RedisPubSubAdapter<String, String>() {
                        @Override
                        public void message(String channel, String message) {
                            if (cacheA.invalidationChannel().equals(channel)) {
                                cacheA.clearLocalKey(message);
                            }
                        }
                    });
            subscriberA.sync().subscribe(cacheA.invalidationChannel());

            String key = RedisEntityIndexCache.userByUsername(TestSessions.REALM_ID, "Alice");
            cacheA.put(key, "user-42");

            // Prove the value is served from L1: wipe L2 only, L1 still answers.
            provider().sync().del(redisEntityKey(key));
            assertEquals("user-42", cacheA.get(key), "L1 should serve the cached mapping");

            // Node B invalidates: removes L2 (already gone) and broadcasts.
            RedisEntityIndexCache cacheB =
                    new RedisEntityIndexCache(provider(), 300, new ConcurrentHashMap<>());
            cacheB.invalidate(key);

            Awaitility.await("invalidation event should clear node A L1")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertNull(cacheA.get(key)));
        } finally {
            subscriberA.close();
        }
    }

    private static String redisEntityKey(String relativeKey) {
        return balbucio.keycloak.cache.redis.common.RedisKeySpace.key(
                RedisEntityIndexCache.KEY_PREFIX_RELATIVE + relativeKey);
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
