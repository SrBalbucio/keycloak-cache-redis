package balbucio.keycloak.cache.redis.integration;

import balbucio.keycloak.cache.redis.connection.LettuceRedisAsync;
import balbucio.keycloak.cache.redis.connection.LettuceRedisSync;
import balbucio.keycloak.cache.redis.connection.RedisAsync;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisMode;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers Redis used by the level-1 provider integration tests.
 *
 * <p>Starts lazily once, keeps the container alive for the whole surefire JVM and shuts it down via a
 * JVM hook. Honors {@code REDIS_TEST_URI} / {@code redis.test.uri} to reuse an external Redis instead
 * of Docker.
 */
public final class IntegrationRedis {

    private static final Object LOCK = new Object();
    private static RedisContainer container;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisConnectionProvider provider;
    private static boolean started;
    /** True when Redis was started by Testcontainers (safe to FLUSHDB). */
    private static boolean ownsInstance;

    private IntegrationRedis() {}

    /** Whether {@link #flush()} may wipe the entire logical database. */
    public static boolean ownsInstance() {
        ensureStarted();
        return ownsInstance;
    }

    public static boolean available() {
        if (externalUri() != null) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static RedisConnectionProvider provider() {
        ensureStarted();
        return provider;
    }

    public static StatefulRedisConnection<String, String> connection() {
        ensureStarted();
        return connection;
    }

    public static RedisClient client() {
        ensureStarted();
        return client;
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (!started) {
                return;
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // ignore
                }
                connection = null;
            }
            if (client != null) {
                try {
                    client.shutdown();
                } catch (Exception ignored) {
                    // ignore
                }
                client = null;
            }
            if (container != null) {
                try {
                    container.stop();
                } catch (Exception ignored) {
                    // ignore
                }
                container = null;
            }
            provider = null;
            started = false;
            ownsInstance = false;
        }
    }

    /**
     * Clears test data. Uses {@code FLUSHDB} only for the ephemeral Testcontainers instance. For an
     * external {@code REDIS_TEST_URI}, requires {@code REDIS_TEST_ALLOW_FLUSH=true} or skips wipe
     * (tests may see leftover keys).
     */
    public static void flush() {
        ensureStarted();
        if (ownsInstance || allowExternalFlush()) {
            connection.sync().flushdb();
            return;
        }
        throw new IllegalStateException(
                "Refusing FLUSHDB on external REDIS_TEST_URI. Start Testcontainers, use a dedicated "
                        + "DB, or set REDIS_TEST_ALLOW_FLUSH=true");
    }

    private static boolean allowExternalFlush() {
        String env = System.getenv("REDIS_TEST_ALLOW_FLUSH");
        if (env != null && ("1".equals(env) || "true".equalsIgnoreCase(env))) {
            return true;
        }
        return Boolean.getBoolean("redis.test.allowFlush");
    }

    private static String externalUri() {
        String env = System.getenv("REDIS_TEST_URI");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String prop = System.getProperty("redis.test.uri", "");
        return prop.isBlank() ? null : prop;
    }

    private static void ensureStarted() {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            String uri = externalUri();
            if (uri == null) {
                container = new RedisContainer(DockerImageName.parse("redis:7.2-alpine"));
                container.start();
                uri = container.getRedisURI();
                ownsInstance = true;
            } else {
                ownsInstance = false;
            }
            client = RedisClient.create(RedisURI.create(uri));
            connection = client.connect();
            RedisSync sync = LettuceRedisSync.of(connection.sync());
            if (!"PONG".equalsIgnoreCase(sync.ping())) {
                throw new IllegalStateException("Redis not reachable at " + uri);
            }
            provider = new RedisConnectionProvider() {
                @Override
                public RedisSync sync() {
                    return LettuceRedisSync.of(connection.sync());
                }

                @Override
                public RedisAsync async() {
                    return LettuceRedisAsync.of(connection.async());
                }

                @Override
                public StatefulRedisPubSubConnection<String, String> connectPubSub() {
                    return client.connectPubSub();
                }

                @Override
                public RedisMode mode() {
                    return RedisMode.STANDALONE;
                }

                @Override
                public String keyPrefix() {
                    return "";
                }

                @Override
                public String casScriptSha() {
                    return null;
                }

                @Override
                public void close() {}
            };
            started = true;
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        try {
                                            shutdown();
                                        } catch (Exception ignored) {
                                            // ignore
                                        }
                                    },
                                    "integration-redis-shutdown"));
        }
    }
}
