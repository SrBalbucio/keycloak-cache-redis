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
        if (IntegrationRedis.available()) {
            return;
        }
        // In CI, skipped ITs are a false green — fail instead of assume.
        if (isCi() || Boolean.getBoolean("redis.test.required")) {
            throw new IllegalStateException(
                    "Redis integration tests require Docker or REDIS_TEST_URI (CI/redis.test.required)");
        }
        assumeTrue(false, "Redis integration test requires Docker or REDIS_TEST_URI");
    }

    private static boolean isCi() {
        return "true".equalsIgnoreCase(System.getenv("CI"))
                || "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }

    @BeforeEach
    void flushRedis() {
        IntegrationRedis.flush();
    }

    protected RedisConnectionProvider provider() {
        return IntegrationRedis.provider();
    }
}
