package balbucio.keycloak.cache.redis.cluster;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import balbucio.keycloak.cache.redis.connection.PubSubReconnect;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.SetArgs;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterListener;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.cluster.infinispan.TaskCallback;
import org.keycloak.common.util.ConcurrentMultivaluedHashMap;
import org.keycloak.models.utils.KeycloakModelUtils;

public class RedisPubsubClusterProvider implements ClusterProvider {

    private static final Logger LOG = Logger.getLogger(RedisPubsubClusterProvider.class);

    public static final String TASK_KEY_PREFIX = "task::";
    static final String CHANNEL_RELATIVE = "cluster:events";
    static final String TASK_FINISHED_CHANNEL_RELATIVE = "cluster:task-finished";
    static final String LOCK_PREFIX_RELATIVE = "cluster:lock:";

    private final RedisSync publisher;
    private final StatefulRedisPubSubConnection<String, String> subscriber;
    private final int clusterStartupTime;
    private final ExecutorService executor;
    private final String nodeId;
    private final String channel;
    private final String taskFinishedChannel;

    private final ConcurrentMultivaluedHashMap<String, ClusterListener> listeners =
            new ConcurrentMultivaluedHashMap<>();
    private final ConcurrentMap<String, TaskCallback> taskCallbacks = new ConcurrentHashMap<>();

    /**
     * Fase 1.2: observa reconnects do canal {@code cluster:events} (métrica + log). Sem ação
     * de limpeza: este provider não mantém L1 próprio e os caches stock convergem sob demanda
     * via listeners do core. Package-visible para testes.
     */
    final RedisConnectionStateListener reconnectListener;

    public RedisPubsubClusterProvider(
            RedisSync publisher,
            StatefulRedisPubSubConnection<String, String> subscriber,
            int clusterStartupTime,
            ExecutorService executor,
            String nodeId) {
        this.publisher = publisher;
        this.subscriber = subscriber;
        this.clusterStartupTime = clusterStartupTime;
        this.executor = executor;
        this.nodeId = nodeId;
        this.channel = RedisKeySpace.key(CHANNEL_RELATIVE);
        this.taskFinishedChannel = RedisKeySpace.key(TASK_FINISHED_CHANNEL_RELATIVE);

        subscriber.addListener(
                new RedisPubSubAdapter<String, String>() {
                    @Override
                    public void message(String ch, String message) {
                        if (channel.equals(ch)) {
                            handleMessage(message);
                        } else if (taskFinishedChannel.equals(ch)) {
                            taskFinished(message);
                        }
                    }
                });
        subscriber.sync().subscribe(channel, taskFinishedChannel);
        LOG.debugf(
                "Subscribed to Redis cluster channels %s,%s (node=%s)",
                channel, taskFinishedChannel, nodeId);
        reconnectListener = PubSubReconnect.reconnectListener("cluster", null);
        PubSubReconnect.attach(subscriber, reconnectListener);
    }

    @Override
    public int getClusterStartupTime() {
        return clusterStartupTime;
    }

    @Override
    public void notify(String taskKey, ClusterEvent event, boolean ignoreSender, DCNotify dcNotify) {
        notify(taskKey, List.of(event), ignoreSender, dcNotify);
    }

    @Override
    public void notify(
            String taskKey,
            Collection<? extends ClusterEvent> events,
            boolean ignoreSender,
            DCNotify dcNotify) {
        try {
            // Fase 1.3: single-site assumido (docs/clustering.md, docs/limitations.md).
            // O dcNotify é transportado no envelope mas sem filtro multi-DC; avisar quando o
            // core pedir roteamento por DC para o comportamento não ficar silencioso.
            if (dcNotify != null && dcNotify != DCNotify.ALL_DCS) {
                LOG.warnf(
                        "Ignoring DCNotify=%s for cluster event %s (single-site deployment assumed)",
                        dcNotify, taskKey);
            }
            String serialized =
                    ClusterEventSerializer.serialize(
                            taskKey, List.copyOf(events), ignoreSender, dcNotify, nodeId);
            LOG.debugf("notify %s: %s", taskKey, serialized);
            Long subscribers = publisher.publish(channel, serialized);
            RedisMetrics.record(RedisMetrics.Cache.CLUSTER, RedisMetrics.Op.PUBLISH);
            RedisMetrics.recordClusterEvent(taskKey, RedisMetrics.ClusterEvent.SENT);
            LOG.debugf("notify published to %s subscribers", subscribers);
        } catch (Exception e) {
            RedisMetrics.recordClusterEvent(taskKey, RedisMetrics.ClusterEvent.PUBLISH_ERROR);
            LOG.errorf(e, "Failed to publish cluster event %s", taskKey);
        }
    }

    private void handleMessage(String message) {
        String eventKeyForMetrics = null;
        try {
            ClusterEventSerializer.ClusterMessage deserialized =
                    ClusterEventSerializer.deserialize(message);
            eventKeyForMetrics = deserialized.getEventKey();
            if (deserialized.getIgnoreSender()
                    && nodeId != null
                    && nodeId.equals(deserialized.getSenderId())) {
                LOG.tracef("Ignoring own cluster event %s", deserialized.getEventKey());
                RedisMetrics.recordClusterEvent(
                        eventKeyForMetrics, RedisMetrics.ClusterEvent.SELF_IGNORED);
                return;
            }

            String eventKey = deserialized.getEventKey();
            List<ClusterListener> cls = listeners.get(eventKey);
            if (cls == null || cls.isEmpty()) {
                RedisMetrics.recordClusterEvent(
                        eventKey, RedisMetrics.ClusterEvent.DROPPED_NO_LISTENER);
                return;
            }
            if (deserialized.getEvents() == null) {
                RedisMetrics.recordClusterEvent(
                        eventKey, RedisMetrics.ClusterEvent.DROPPED_NULL_EVENTS);
                return;
            }
            for (ClusterEvent event : deserialized.getEvents()) {
                cls.forEach(event);
            }
            RedisMetrics.recordClusterEvent(eventKey, RedisMetrics.ClusterEvent.DELIVERED);
            RedisMetrics.recordClusterLag(eventKey, deserialized.getSentAtMillis());
        } catch (Exception e) {
            RedisMetrics.recordClusterEvent(
                    eventKeyForMetrics, RedisMetrics.ClusterEvent.DESER_ERROR);
            LOG.error("Failed to handle Redis cluster event", e);
        }
    }

    @Override
    public void registerListener(String taskKey, ClusterListener task) {
        LOG.debugf("Registering cluster listener for %s", taskKey);
        listeners.add(taskKey, task);
    }

    private static final String UNLOCK_SCRIPT =
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    @Override
    public <T> ExecutionResult<T> executeIfNotExecuted(
            String taskKey, int lifespanSeconds, Callable<T> task) {
        String lockKey = RedisKeySpace.key(LOCK_PREFIX_RELATIVE + taskKey);
        String taskId = KeycloakModelUtils.generateId();

        try {
            String lockResult =
                    publisher.set(lockKey, taskId, SetArgs.Builder.nx().ex(lifespanSeconds));
            if ("OK".equals(lockResult)) {
                try {
                    T result = task.call();
                    return ExecutionResult.executed(result);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException("Unexpected exception when executed task " + taskKey, e);
                } finally {
                    try {
                        publisher.eval(
                                UNLOCK_SCRIPT,
                                io.lettuce.core.ScriptOutputType.INTEGER,
                                new String[] {lockKey},
                                taskId);
                    } catch (Exception unlockError) {
                        LOG.debugf(unlockError, "Failed to release cluster lock %s", lockKey);
                    }
                    notifyTaskFinished(taskKey);
                }
            }
            return ExecutionResult.notExecuted();
        } catch (Exception e) {
            // Distinguish Redis errors from "lock held" so callers can retry / alert.
            LOG.warnf(e, "Error acquiring or running cluster lock for %s", taskKey);
            throw new RuntimeException("Redis error during cluster lock for " + taskKey, e);
        }
    }

    /**
     * Completes waiters registered for {@code taskKey} (with {@link #TASK_KEY_PREFIX}). Idempotent
     * if the callback was already removed.
     */
    void taskFinished(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return;
        }
        TaskCallback callback = taskCallbacks.remove(taskKey);
        if (callback != null) {
            callback.setSuccess(true);
            callback.getTaskCompletedLatch().countDown();
            LOG.debugf("Cluster task finished: %s", taskKey);
        }
    }

    private void notifyTaskFinished(String taskKey) {
        String callbackKey = TASK_KEY_PREFIX + taskKey;
        taskFinished(callbackKey);
        try {
            publisher.publish(taskFinishedChannel, callbackKey);
            RedisMetrics.record(RedisMetrics.Cache.CLUSTER, RedisMetrics.Op.PUBLISH);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to publish task-finished for %s", taskKey);
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Future<Boolean> executeIfNotExecutedAsync(
            String taskKey, int taskTimeoutInSeconds, Callable task) {
        TaskCallback newCallback = new TaskCallback();
        TaskCallback callback = registerTaskCallback(TASK_KEY_PREFIX + taskKey, newCallback);

        if (newCallback == callback) {
            Callable<Boolean> wrappedTask =
                    () -> {
                        long startedNanos = System.nanoTime();
                        boolean executed =
                                executeIfNotExecuted(taskKey, taskTimeoutInSeconds, task).isExecuted();
                        if (!executed) {
                            LOG.infof(
                                    "Task already in progress on other cluster node. Will wait until finished");
                        }
                        // Fase 1.4: espera fatiada em vez de um await único. Se o lock sumir
                        // (holder morreu sem unlock/publish) em dois slices seguidos sem o
                        // task-finished chegar, desiste antes do timeout cheio. O teto total
                        // continua taskTimeoutInSeconds — o contrato de espera máxima não muda.
                        String lockKey = RedisKeySpace.key(LOCK_PREFIX_RELATIVE + taskKey);
                        long deadlineNanos =
                                startedNanos + TimeUnit.SECONDS.toNanos(Math.max(0, taskTimeoutInSeconds));
                        long sliceNanos =
                                TimeUnit.SECONDS.toNanos(Math.min(2, Math.max(1, taskTimeoutInSeconds)));
                        int lockAbsentStreak = 0;
                        boolean latchCounted = false;
                        while (System.nanoTime() < deadlineNanos) {
                            long remainingNanos = deadlineNanos - System.nanoTime();
                            if (callback.getTaskCompletedLatch()
                                    .await(Math.min(sliceNanos, remainingNanos), TimeUnit.NANOSECONDS)) {
                                latchCounted = true;
                                break;
                            }
                            if (callback.getTaskCompletedLatch().getCount() == 0) {
                                latchCounted = true;
                                break;
                            }
                            boolean lockPresent = true;
                            try {
                                Long exists = publisher.exists(lockKey);
                                lockPresent = exists != null && exists > 0;
                            } catch (Exception e) {
                                // Fail-open: sem leitura do lock, segue esperando até o teto.
                                LOG.debugf(e, "Failed to probe cluster lock %s", lockKey);
                                lockAbsentStreak = 0;
                                continue;
                            }
                            if (lockPresent) {
                                lockAbsentStreak = 0;
                            } else if (++lockAbsentStreak >= 2) {
                                LOG.infof(
                                        "Cluster lock %s vanished without task completion — holder likely died; giving up early",
                                        lockKey);
                                break;
                            }
                        }
                        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                        RedisMetrics.recordClusterTask(
                                taskKey,
                                latchCounted && callback.isSuccess()
                                        ? RedisMetrics.ClusterTask.FINISHED
                                        : RedisMetrics.ClusterTask.TIMEOUT,
                                waitedMillis);
                        return callback.isSuccess();
                    };
            Future<Boolean> future = executor.submit(wrappedTask);
            callback.setFuture(future);
        } else {
            LOG.infof("Task already in progress on this cluster node. Will wait until finished");
        }
        return callback.getFuture();
    }

    TaskCallback registerTaskCallback(String taskKey, TaskCallback callback) {
        TaskCallback existing = taskCallbacks.putIfAbsent(taskKey, callback);
        return existing == null ? callback : existing;
    }

    /**
     * Intentionally a no-op, mirroring stock {@code InfinispanClusterProvider.close()}.
     *
     * <p>The provider instance is shared across sessions (created once in the factory) and
     * {@code DefaultKeycloakSession.close()} closes every provider created within a session —
     * including bootstrap sessions closed ~1s after boot. Closing the subscriber here would
     * silently kill cross-node invalidation for the whole node while the factory keeps
     * returning the dead instance. Subscriber lifecycle belongs to
     * {@link RedisPubsubClusterProviderFactory#close()}.
     */
    @Override
    public void close() {
        LOG.debugf("Ignoring session-scoped close of shared ClusterProvider (node=%s)", nodeId);
    }

    /** Closes the shared subscriber. Called only by the factory at server shutdown. */
    void closeSubscriber() {
        try {
            if (subscriber != null && subscriber.isOpen()) {
                PubSubReconnect.detach(subscriber, reconnectListener);
                try {
                    subscriber.sync().unsubscribe(channel, taskFinishedChannel);
                } catch (Exception e) {
                    LOG.debug("Error unsubscribing from cluster channel", e);
                }
                subscriber.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing cluster pubsub subscriber", e);
        }
    }
}
