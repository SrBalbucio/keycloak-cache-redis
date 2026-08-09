package balbucio.keycloak.cache.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.connection.RedisAsync;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;

/**
 * Unit-of-work for Redis hash entities, enlisted via {@code enlistAfterCompletion}.
 */
public class RedisChangelogTransaction<K extends Key, A extends MapEntity> extends AbstractKeycloakTransaction {

    private static final Logger LOG = Logger.getLogger(RedisChangelogTransaction.class);

    private final KeycloakSession session;
    private final RedisConnectionProvider connection;
    private final AdapterSupplier<K, A> adapterSupplier;
    private final Function<A, Collection<IndexUpdate>> indexFunction;

    private final Map<K, A> cache = new LinkedHashMap<>();
    private final Map<K, A> toDelete = new LinkedHashMap<>();

    public RedisChangelogTransaction(
            KeycloakSession session,
            RedisConnectionProvider connection,
            AdapterSupplier<K, A> adapterSupplier,
            Function<A, Collection<IndexUpdate>> indexFunction) {
        this.session = session;
        this.connection = connection;
        this.adapterSupplier = adapterSupplier;
        this.indexFunction = indexFunction == null ? a -> List.of() : indexFunction;
        session.getTransactionManager().enlistAfterCompletion(this);
    }

    public A create(K key, A entity) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        cache.put(key, entity);
        toDelete.remove(key);
        return entity;
    }

    public A get(K key) {
        A cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        if (toDelete.containsKey(key)) {
            return null;
        }
        Map<String, String> hash = connection.sync().hgetall(key.key());
        RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.HGETALL);
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        MapEntity entity = MapEntity.fromRedis(hash);
        if (entity.isExpired(Time.currentTimeMillis())) {
            connection.sync().del(key.key());
            RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.DEL);
            return null;
        }
        A adapter = adapterSupplier.create(key, entity);
        cache.put(key, adapter);
        return adapter;
    }

    public A getIfPresent(K key) {
        return cache.get(key);
    }

    public Map<K, A> getAll(Collection<K> keys) {
        Map<K, A> result = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }
        List<K> missing = new ArrayList<>();
        for (K key : keys) {
            if (toDelete.containsKey(key)) {
                continue;
            }
            A cached = cache.get(key);
            if (cached != null) {
                result.put(key, cached);
            } else {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }

        RedisAsync async = connection.async();
        async.setAutoFlushCommands(false);
        try {
            List<CompletableFuture<Map<String, String>>> futures = new ArrayList<>();
            for (K key : missing) {
                futures.add(async.hgetall(key.key()));
            }
            async.flushCommands();
            long now = Time.currentTimeMillis();
            for (int i = 0; i < missing.size(); i++) {
                K key = missing.get(i);
                Map<String, String> hash = futures.get(i).join();
                if (hash == null || hash.isEmpty()) {
                    continue;
                }
                MapEntity entity = MapEntity.fromRedis(hash);
                if (entity.isExpired(now)) {
                    continue;
                }
                A adapter = adapterSupplier.create(key, entity);
                cache.put(key, adapter);
                result.put(key, adapter);
            }
        } finally {
            async.setAutoFlushCommands(true);
        }
        return result;
    }

    public void delete(K key) {
        A entity = cache.remove(key);
        if (entity == null) {
            entity = get(key);
            cache.remove(key);
        }
        if (entity != null) {
            entity.markForDelete();
            toDelete.put(key, entity);
        } else {
            // ensure key is removed even if not loaded
            MapEntity tombstone = MapEntity.createNew();
            tombstone.markForDelete();
            toDelete.put(key, adapterSupplier.create(key, tombstone));
        }
    }

    public Collection<A> getCachedEntities() {
        return List.copyOf(cache.values());
    }

    @Override
    protected void commitImpl() {
        RedisSync sync = connection.sync();

        // Deletes + index removals
        if (!toDelete.isEmpty()) {
            runIndexBatch(
                    sync,
                    () -> {
                        for (Map.Entry<K, A> e : toDelete.entrySet()) {
                            sync.del(e.getKey().key());
                            for (IndexUpdate index : indexFunction.apply(e.getValue())) {
                                if (index.member() != null) {
                                    sync.srem(index.indexKey(), index.member());
                                }
                            }
                        }
                    });
        }

        // Dirty entities via CAS
        for (Map.Entry<K, A> e : cache.entrySet()) {
            A entity = e.getValue();
            if (entity.isMarkedForDelete() || !entity.hasPendingChanges()) {
                continue;
            }
            commitEntity(e.getKey(), entity);
        }

        cache.clear();
        toDelete.clear();
    }

    private void commitEntity(K key, A entity) {
        int attempts = 0;
        while (attempts < Constants.CAS_MAX_RETRIES) {
            attempts++;
            Long expected = entity.isCreated() ? null : entity.getLoadedVersion();
            Long expiration = entity.getExpiration();
            long expireAt = expiration == null ? 0L : expiration;

            Map<String, String> toSet = new HashMap<>(entity.pendingSets());
            toSet.remove(Constants.VERSION_FIELD);
            Set<String> toDeleteFields = new HashSet<>(entity.pendingDeletes());
            toDeleteFields.remove(Constants.VERSION_FIELD);
            Map<String, Long> increments = entity.pendingIncrements();

            long result =
                    RedisHashCas.hsetex(
                            connection, key.key(), expected, expireAt, toSet, toDeleteFields, increments);
            RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.HSETEX);
            if (result == 1) {
                long newVersion = expected == null ? 1L : expected + 1L;
                entity.clearChangeTracking(newVersion);
                writeIndexes(entity, true);
                return;
            }
            if (result == -1) {
                LOG.debugf("CAS create conflict for %s, rebasing", key.key());
            } else if (result == 0) {
                LOG.debugf("CAS version mismatch for %s, rebasing (attempt %d)", key.key(), attempts);
            } else {
                throw new IllegalStateException("Redis CAS returned invalid code " + result + " for " + key.key());
            }

            Map<String, String> fresh = connection.sync().hgetall(key.key());
            if (fresh == null || fresh.isEmpty()) {
                entity.rebase(null);
                if (expected == null) {
                    continue;
                }
                entity.rebase(null);
            } else {
                entity.rebase(fresh);
            }
        }
        throw new IllegalStateException(
                "Failed to commit entity after " + Constants.CAS_MAX_RETRIES + " CAS retries: " + key.key());
    }

    private void writeIndexes(A entity, boolean add) {
        Collection<IndexUpdate> indexes = indexFunction.apply(entity);
        if (indexes.isEmpty()) {
            return;
        }
        RedisSync sync = connection.sync();
        runIndexBatch(
                sync,
                () -> {
                    for (IndexUpdate index : indexes) {
                        if (index.member() == null) {
                            continue;
                        }
                        if (add) {
                            sync.sadd(index.indexKey(), index.member());
                        } else {
                            sync.srem(index.indexKey(), index.member());
                        }
                    }
                });
    }

    private void runIndexBatch(RedisSync sync, Runnable commands) {
        if (!sync.supportsTransactions()) {
            commands.run();
            RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.SADD);
            return;
        }
        sync.multi();
        try {
            commands.run();
            sync.exec();
            RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.SADD);
        } catch (RuntimeException ex) {
            try {
                sync.discard();
            } catch (RuntimeException ignored) {
                // ignore
            }
            throw ex;
        }
    }

    @Override
    protected void rollbackImpl() {
        cache.clear();
        toDelete.clear();
    }

    public record IndexUpdate(String indexKey, String member) {}
}
