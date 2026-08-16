package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.entity.RedisEntityIndexCache;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lost-invalidation coverage: what happens when the pub/sub invalidation message never reaches a
 * node (subscriber disconnected at publish time).
 *
 * <p>Characterized behavior: the L1 map has no TTL of its own, so a lost invalidation leaves L1
 * stale until {@code clearLocal()}/restart; the L2 (Redis) entry TTL is the only automatic bound,
 * and it only helps nodes with a cold L1. Resubscribing restores the invalidation flow but does
 * NOT retroactively replay missed invalidations.
 */
@Tag("chaos")
class RedisLostInvalidationIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void lostInvalidationLeavesLocalCacheStaleUntilCleared() {
        RedisEntityIndexCache cacheA =
                new RedisEntityIndexCache(provider(), 300, new ConcurrentHashMap<>());
        StatefulRedisPubSubConnection<String, String> subscriberA = provider().connectPubSub();
        wireInvalidations(subscriberA, cacheA);

        String key = RedisEntityIndexCache.userByUsername(TestSessions.REALM_ID, "bob");
        cacheA.put(key, "user-7");
        assertEquals("user-7", cacheA.get(key));

        // Node A loses its invalidation channel (disconnect, network partition, ...).
        subscriberA.close();

        // Node B invalidates while A is deaf: L2 removed, broadcast lost.
        RedisEntityIndexCache cacheB =
                new RedisEntityIndexCache(provider(), 300, new ConcurrentHashMap<>());
        cacheB.invalidate(key);

        assertNull(provider().sync().get(redisEntityKey(key)), "L2 entry must be gone");
        assertEquals(
                "user-7",
                cacheA.get(key),
                "CHARACTERIZED HAZARD: L1 keeps serving the stale mapping after a lost invalidation");

        // Only an explicit clear (restart / full invalidation) recovers node A.
        cacheA.clearLocal();
        assertNull(cacheA.get(key));
    }

    @Test
    void l2TtlBoundsStalenessForNodesWithColdLocalCache() {
        long ttlSeconds = 1;
        RedisEntityIndexCache cacheA =
                new RedisEntityIndexCache(provider(), ttlSeconds, new ConcurrentHashMap<>());
        String key = RedisEntityIndexCache.userByUsername(TestSessions.REALM_ID, "carol");
        cacheA.put(key, "user-9");

        // Wait out the L2 TTL.
        Awaitility.await("L2 entry should expire")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> provider().sync().exists(redisEntityKey(key)) == 0L);

        // A node that never cached locally (cold L1) is protected by the L2 TTL.
        RedisEntityIndexCache coldNode =
                new RedisEntityIndexCache(provider(), ttlSeconds, new ConcurrentHashMap<>());
        assertNull(coldNode.get(key), "cold node must not resurrect an expired mapping");

        // But node A's L1 still answers from memory — L1 staleness is unbounded without
        // invalidation delivery or an explicit clear.
        assertEquals("user-9", cacheA.get(key));
    }

    @Test
    void invalidationFlowsAgainAfterResubscribeButMissedOnesAreNotReplayed() {
        RedisEntityIndexCache cacheA =
                new RedisEntityIndexCache(provider(), 300, new ConcurrentHashMap<>());
        StatefulRedisPubSubConnection<String, String> subscriberA = provider().connectPubSub();
        wireInvalidations(subscriberA, cacheA);

        String key = RedisEntityIndexCache.userByUsername(TestSessions.REALM_ID, "dave");
        cacheA.put(key, "user-11");

        subscriberA.close();
        RedisEntityIndexCache cacheB =
                new RedisEntityIndexCache(provider(), 300, new ConcurrentHashMap<>());
        cacheB.invalidate(key);
        assertEquals("user-11", cacheA.get(key), "invalidation lost while disconnected");

        // Node A resubscribes; node B republishes the mapping and invalidates again.
        StatefulRedisPubSubConnection<String, String> resubscribed = provider().connectPubSub();
        try {
            wireInvalidations(resubscribed, cacheA);
            cacheB.put(key, "user-11");
            cacheB.invalidate(key);

            Awaitility.await("new invalidation should be delivered after resubscribe")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(() -> assertNull(cacheA.get(key)));
        } finally {
            resubscribed.close();
        }
    }

    private static void wireInvalidations(
            StatefulRedisPubSubConnection<String, String> subscriber, RedisEntityIndexCache cache) {
        subscriber.addListener(
                new RedisPubSubAdapter<String, String>() {
                    @Override
                    public void message(String channel, String message) {
                        if (cache.invalidationChannel().equals(channel)) {
                            cache.clearLocalKey(message);
                        }
                    }
                });
        subscriber.sync().subscribe(cache.invalidationChannel());
    }

    private static String redisEntityKey(String relativeKey) {
        return RedisKeySpace.key(RedisEntityIndexCache.KEY_PREFIX_RELATIVE + relativeKey);
    }
}
