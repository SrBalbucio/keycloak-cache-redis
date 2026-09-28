package balbucio.keycloak.cache.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Operation counters exposed on Keycloak's Micrometer registry ({@code /metrics}).
 */
public final class RedisMetrics {

    public static final String CACHE_TAG = "cache";
    public static final String OPERATION_TAG = "op";

    public static final String METRIC_NAME = "vendor.lettuce.cache";

    /**
     * Fase 0: observabilidade do canal {@code cluster:events} (transporte da invalidação dos
     * caches locais Infinispan com {@code KC_CACHE=local}). Contadores por {@code eventKey} para
     * confrontar PUBLISH vs DELIVER e detectar perda sistemática antes de qualquer mudança de
     * semântica. Todas as gravações são fail-open (nunca quebram o request path).
     */
    public static final String CLUSTER_EVENTS_METRIC = "vendor.lettuce.cluster.events";
    public static final String CLUSTER_LAG_METRIC = "vendor.lettuce.cluster.lag";
    public static final String CLUSTER_TASK_METRIC = "vendor.lettuce.cluster.task";

    private RedisMetrics() {}

    public static void record(String cache, String operation) {
        if (cache == null || operation == null) {
            return;
        }
        try {
            Counter.builder(METRIC_NAME)
                    .description("Redis cache operation counters")
                    .baseUnit("operations")
                    .tag(CACHE_TAG, cache)
                    .tag(OPERATION_TAG, operation)
                    .register(Metrics.globalRegistry)
                    .increment();
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
        /** Public {@code redisCache} SPI for third-party extensions. */
        public static final String CACHE_API = "cacheApi";

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
        /** Cache outcome: successful read of a usable entry (L1 or L2). */
        public static final String HIT = "HIT";
        /** Cache outcome: absent or stale entry. */
        public static final String MISS = "MISS";
        /** Cache outcome: Redis/deser failure treated as miss (fail-open). */
        public static final String ERROR = "ERROR";
        /** Optimistic CAS conflict that triggered a rebase + retry. */
        public static final String CAS_RETRY = "CAS_RETRY";
        /** Optimistic CAS exhausted retries without a successful write. */
        public static final String CAS_FAIL = "CAS_FAIL";

        private Op() {}
    }

    public static final class ClusterEvent {
        public static final String SENT = "sent";
        public static final String DELIVERED = "delivered";
        public static final String SELF_IGNORED = "self_ignored";
        public static final String DROPPED_NO_LISTENER = "dropped_no_listener";
        public static final String DROPPED_NULL_EVENTS = "dropped_null_events";
        public static final String DESER_ERROR = "deser_error";
        public static final String PUBLISH_ERROR = "publish_error";
        /** PUBSUB subscription reconnected after an outage (some invalidations may have been lost). */
        public static final String RECONNECTED = "reconnected";
        /** Node-local L1 cleared as a reconnect reconciliation (see PubSubReconnect). */
        public static final String RESYNC_CLEARED = "resync_cleared";

        private ClusterEvent() {}
    }

    public static final class ClusterTask {
        public static final String FINISHED = "finished";
        public static final String TIMEOUT = "timeout";

        private ClusterTask() {}
    }

    private static String safeEventKey(String eventKey) {
        return eventKey == null || eventKey.isBlank() ? "unknown" : eventKey;
    }

    /** Conta um evento de cluster publicado/recebido/descartado, segmentado por eventKey. Fail-open. */
    public static void recordClusterEvent(String eventKey, String outcome) {
        if (outcome == null) {
            return;
        }
        try {
            Counter.builder(CLUSTER_EVENTS_METRIC)
                    .description("Redis cluster bus events by eventKey")
                    .baseUnit("events")
                    .tag("eventKey", safeEventKey(eventKey))
                    .tag("outcome", outcome)
                    .register(Metrics.globalRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // metrics must never break the request path
        }
    }

    /** Distribuição do lag publish→deliver do cluster bus. Ignora sentAt ausente/antigo (<=0). Fail-open. */
    public static void recordClusterLag(String eventKey, long sentAtMillis) {
        if (sentAtMillis <= 0) {
            return;
        }
        try {
            long lag = System.currentTimeMillis() - sentAtMillis;
            if (lag < 0) {
                return;
            }
            Timer.builder(CLUSTER_LAG_METRIC)
                    .description("Redis cluster event publish-to-deliver lag")
                    .tag("eventKey", safeEventKey(eventKey))
                    .register(Metrics.globalRegistry)
                    .record(Duration.ofMillis(lag));
        } catch (RuntimeException ignored) {
            // metrics must never break the request path
        }
    }

    /** Resultado da espera de task async do cluster (finished/timeout). Fail-open. */
    public static void recordClusterTask(String taskKey, String outcome, long waitedMillis) {
        if (outcome == null) {
            return;
        }
        try {
            Timer.builder(CLUSTER_TASK_METRIC)
                    .description("Redis cluster async task wait")
                    .tag("task", taskKey == null || taskKey.isBlank() ? "unknown" : taskKey)
                    .tag("outcome", outcome)
                    .register(Metrics.globalRegistry)
                    .record(Duration.ofMillis(Math.max(0, waitedMillis)));
        } catch (RuntimeException ignored) {
            // metrics must never break the request path
        }
    }
}
