package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for level-1 Redis integration tests. Skips cleanly when no Redis is available
 * (no Docker and no {@code REDIS_TEST_URI}) and flushes the shared database before each test.
 */
public abstract class AbstractRedisIntegrationTest {

    @BeforeAll
    static void requireRedis() {
        assumeTrue(IntegrationRedis.available(), "Redis integration test requires Docker or REDIS_TEST_URI");
    }

    @BeforeEach
    void flushRedis() {
        IntegrationRedis.connection().sync().flushdb();
    }

    protected RedisConnectionProvider provider() {
        return IntegrationRedis.provider();
    }
}
