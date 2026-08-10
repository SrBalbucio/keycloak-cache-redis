package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.Test;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;

import balbucio.keycloak.cache.redis.compatibility.RedisPublicKeyStorageProvider;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;

class RedisPublicKeyStorageProviderIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void sharesKeysAcrossProvidersViaRedis() throws Exception {
        RedisConnectionProvider conn = provider();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, PublicKeysWrapper> l1a = new ConcurrentHashMap<>();
        Map<String, PublicKeysWrapper> l1b = new ConcurrentHashMap<>();

        RedisPublicKeyStorageProvider nodeA =
                new RedisPublicKeyStorageProvider(conn, mapper, 3600, l1a);
        RedisPublicKeyStorageProvider nodeB =
                new RedisPublicKeyStorageProvider(conn, mapper, 3600, l1b);

        AtomicInteger loads = new AtomicInteger();
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        PublicKeyLoader loader =
                () -> {
                    loads.incrementAndGet();
                    KeyWrapper key = new KeyWrapper();
                    key.setKid("k1");
                    key.setAlgorithm("RS256");
                    key.setType("RSA");
                    key.setUse(KeyUse.SIG);
                    key.setPublicKey(pair.getPublic());
                    return new PublicKeysWrapper(List.of(key));
                };

        KeyWrapper fromA = nodeA.getPublicKey("model-1", "k1", "RS256", loader);
        assertNotNull(fromA);
        assertEquals(1, loads.get());

        // Clear node B L1 so it must hit Redis L2 (no second loader call)
        l1b.clear();
        KeyWrapper fromB = nodeB.getPublicKey("model-1", "k1", "RS256", loader);
        assertNotNull(fromB);
        assertEquals(1, loads.get(), "second node should load from Redis, not loader");
        assertEquals(pair.getPublic(), fromB.getPublicKey());
    }

    @Test
    void reloadBroadcastClearsRemoteL1() throws Exception {
        RedisConnectionProvider conn = provider();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, PublicKeysWrapper> l1a = new ConcurrentHashMap<>();
        Map<String, PublicKeysWrapper> l1b = new ConcurrentHashMap<>();

        RedisPublicKeyStorageProvider nodeA =
                new RedisPublicKeyStorageProvider(conn, mapper, 3600, l1a);
        RedisPublicKeyStorageProvider nodeB =
                new RedisPublicKeyStorageProvider(conn, mapper, 3600, l1b);

        String channel = RedisKeySpace.key(RedisPublicKeyStorageProvider.INVALIDATION_CHANNEL_RELATIVE);
        CountDownLatch latch = new CountDownLatch(1);
        try (StatefulRedisPubSubConnection<String, String> sub = conn.connectPubSub()) {
            sub.addListener(
                    new RedisPubSubAdapter<String, String>() {
                        @Override
                        public void message(String ch, String message) {
                            if (channel.equals(ch)) {
                                nodeB.clearLocal(message);
                                latch.countDown();
                            }
                        }
                    });
            sub.sync().subscribe(channel);
            Thread.sleep(200);

            AtomicInteger loads = new AtomicInteger();
            PublicKeyLoader loader =
                    () -> {
                        loads.incrementAndGet();
                        KeyWrapper key = new KeyWrapper();
                        key.setKid("k2");
                        key.setAlgorithm("RS256");
                        key.setType("RSA");
                        key.setUse(KeyUse.SIG);
                        key.setPublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic());
                        return new PublicKeysWrapper(List.of(key));
                    };

            nodeA.getPublicKey("model-2", "k2", "RS256", loader);
            nodeB.getPublicKey("model-2", "k2", "RS256", loader);
            assertTrue(l1b.containsKey("model-2"));

            assertTrue(nodeA.reloadKeys("model-2", loader));
            assertTrue(latch.await(5, TimeUnit.SECONDS), "expected invalidation pubsub");
            assertTrue(!l1b.containsKey("model-2") || loads.get() >= 2);
        }
    }

    @Test
    void localHitAvoidsRedisReread() throws Exception {
        RedisConnectionProvider conn = provider();
        Map<String, PublicKeysWrapper> l1 = new ConcurrentHashMap<>();
        RedisPublicKeyStorageProvider provider =
                new RedisPublicKeyStorageProvider(conn, new ObjectMapper(), 3600, l1);

        AtomicInteger loads = new AtomicInteger();
        PublicKeyLoader loader =
                () -> {
                    loads.incrementAndGet();
                    KeyWrapper key = new KeyWrapper();
                    key.setKid("k3");
                    key.setType("RSA");
                    key.setAlgorithm("RS256");
                    key.setUse(KeyUse.SIG);
                    key.setPublicKey(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic());
                    return new PublicKeysWrapper(List.of(key));
                };

        KeyWrapper first = provider.getPublicKey("model-3", "k3", "RS256", loader);
        KeyWrapper second = provider.getPublicKey("model-3", "k3", "RS256", loader);
        assertSame(first.getPublicKey(), second.getPublicKey());
        assertEquals(1, loads.get());
    }
}
