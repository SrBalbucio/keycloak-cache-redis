package balbucio.keycloak.cache.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;

/**
 * Operation counters exposed on Keycloak's Micrometer registry ({@code /metrics}).
 */
public final class RedisMetrics {

    public static final String CACHE_TAG = "cache";
    public static final String OPERATION_TAG = "op";

    private static final String METRIC_NAME = "vendor.lettuce.cache";

    private static final Meter.MeterProvider<Counter> COUNTER =
            Counter.builder(METRIC_NAME)
                    .description("Redis cache operation counters")
                    .baseUnit("operations")
                    .withRegistry(Metrics.globalRegistry);

    private RedisMetrics() {}

    public static void record(String cache, String operation) {
        if (cache == null || operation == null) {
            return;
        }
        try {
            COUNTER.withTags(CACHE_TAG, cache, OPERATION_TAG, operation).increment();
        } catch (RuntimeException ignored) {
            // metrics must never break the request path
        }
    }

    public static final class Cache {
        public static final String USER_SESSION = "userSession";
        public static final String CLIENT_SESSION = "clientSession";
        public static final String AUTH_SESSION = "authSession";
        public static final String LOGIN_FAILURE = "loginFailure";
        public static final String SINGLE_USE = "singleUse";
        public static final String CLUSTER = "cluster";
        public static final String AUTHZ = "authz";
        public static final String AUTHZ_GEN = "authzGen";
        public static final String PUBLIC_KEYS = "publicKeys";
        public static final String ENTITY = "entity";
        public static final String GENERIC = "generic";

        private Cache() {}
    }

    public static final class Op {
        public static final String HGETALL = "HGETALL";
        public static final String HSETEX = "HSETEX";
        public static final String HSET = "HSET";
        public static final String SADD = "SADD";
        public static final String SREM = "SREM";
        public static final String DEL = "DEL";
        public static final String EVAL = "EVAL";
        public static final String PUBLISH = "PUBLISH";
        public static final String SMEMBERS = "SMEMBERS";
        public static final String GET = "GET";
        public static final String SET = "SET";
        public static final String INCR = "INCR";

        private Op() {}
    }
}
