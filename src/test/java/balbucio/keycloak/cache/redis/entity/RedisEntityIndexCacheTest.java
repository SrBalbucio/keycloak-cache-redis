package balbucio.keycloak.cache.redis.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import balbucio.keycloak.cache.redis.integration.AbstractRedisIntegrationTest;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RedisEntityIndexCacheTest extends AbstractRedisIntegrationTest {

    @Test
    void putGetRemoveAcrossL1AndRedis() {
        RedisEntityIndexCache cache =
                new RedisEntityIndexCache(provider(), 60, new ConcurrentHashMap<>());

        String key = RedisEntityIndexCache.userByUsername("realm-1", "Alice");
        cache.put(key, "user-42");
        assertEquals("user-42", cache.get(key));

        cache.clearLocal();
        assertEquals("user-42", cache.get(key), "should reload from Redis after L1 clear");

        cache.remove(key);
        assertNull(cache.get(key));
    }
}
