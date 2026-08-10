package balbucio.keycloak.cache.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.connection.RedisAsync;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;

/**
 * Unit-of-work for Redis hash entities, enlisted via {@code enlistAfterCompletion}.
 *
 * <p>Tracks loaded index memberships so updates SREM stale members, cleans indexes when entities
 * expire on read, and commits hash + index deltas atomically via Lua when Redis slots allow.
 */
public class RedisChangelogTransaction<K extends Key, A extends MapEntity> extends AbstractKeycloakTransaction {

    private static final Logger LOG = Logger.getLogger(RedisChangelogTransaction.class);

    private final RedisConnectionProvider connection;
    private final AdapterSupplier<K, A> adapterSupplier;
    private final Function<A, Collection<IndexUpdate>> indexFunction;

    private final Map<K, A> cache = new LinkedHashMap<>();
    private final Map<K, PendingDelete<K, A>> toDelete = new LinkedHashMap<>();
    /** Index memberships observed when the entity was loaded (empty for creates). */
    private final Map<K, Set<IndexUpdate>> loadedIndexes = new LinkedHashMap<>();

    public RedisChangelogTransaction(
            KeycloakSession session,
            RedisConnectionProvider connection,
            AdapterSupplier<K, A> adapterSupplier,
            Function<A, Collection<IndexUpdate>> indexFunction) {
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
        loadedIndexes.put(key, Set.of());
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
            purgeExpired(key, entity);
            return null;
        }
        A adapter = adapterSupplier.create(key, entity);
        cache.put(key, adapter);
        rememberLoadedIndexes(key, adapter);
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
                    purgeExpired(key, entity);
                    continue;
                }
                A adapter = adapterSupplier.create(key, entity);
                cache.put(key, adapter);
                rememberLoadedIndexes(key, adapter);
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
            // Prefer loaded indexes (pre-mutation); fall back to current computation.
            Set<IndexUpdate> loaded = loadedIndexes.get(key);
            List<IndexUpdate> removals =
                    loaded != null && !loaded.isEmpty()
                            ? new ArrayList<>(loaded)
                            : new ArrayList<>(indexFunction.apply(entity));
            entity.markForDelete();
            toDelete.put(key, new PendingDelete<>(entity, removals));
            loadedIndexes.remove(key);
        } else {
            MapEntity tombstone = MapEntity.createNew();
            tombstone.markForDelete();
            toDelete.put(key, new PendingDelete<>(adapterSupplier.create(key, tombstone), List.of()));
        }
    }

    public Collection<A> getCachedEntities() {
        return List.copyOf(cache.values());
    }

    @Override
    protected void commitImpl() {
        // Deletes + index removals (atomic when slots allow)
        for (Map.Entry<K, PendingDelete<K, A>> e : toDelete.entrySet()) {
            List<RedisHashCas.IndexOp> removals = new ArrayList<>();
            for (IndexUpdate index : e.getValue().indexRemovals()) {
                if (index.member() != null) {
                    removals.add(toRemoveOp(index));
                }
            }
            RedisHashCas.deleteWithIndexes(connection, e.getKey().key(), removals);
            RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.DEL);
        }

        // Dirty entities via CAS (+ index deltas)
        for (Map.Entry<K, A> e : cache.entrySet()) {
            A entity = e.getValue();
            if (entity.isMarkedForDelete() || !entity.hasPendingChanges()) {
                continue;
            }
            commitEntity(e.getKey(), entity);
        }

        cache.clear();
        toDelete.clear();
        loadedIndexes.clear();
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

            Set<IndexUpdate> previous = loadedIndexes.getOrDefault(key, Set.of());
            Set<IndexUpdate> next = new LinkedHashSet<>(indexFunction.apply(entity));
            List<RedisHashCas.IndexOp> adds = new ArrayList<>();
            List<RedisHashCas.IndexOp> removes = new ArrayList<>();
            for (IndexUpdate idx : next) {
                if (idx.member() != null && !previous.contains(idx)) {
                    adds.add(toAddOp(idx));
                }
            }
            for (IndexUpdate idx : previous) {
                if (idx.member() != null && !next.contains(idx)) {
                    removes.add(toRemoveOp(idx));
                }
            }
            // Always refresh TTL / ensure membership for current indexes on successful write
            for (IndexUpdate idx : next) {
                if (idx.member() != null && previous.contains(idx)) {
                    adds.add(toAddOp(idx));
                }
            }

            long result =
                    RedisHashCas.hsetex(
                            connection,
                            key.key(),
                            expected,
                            expireAt,
                            toSet,
                            toDeleteFields,
                            increments,
                            adds,
                            removes);
            RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.HSETEX);
            if (result == 1) {
                long newVersion = expected == null ? 1L : expected + 1L;
                entity.clearChangeTracking(newVersion);
                loadedIndexes.put(key, Set.copyOf(next));
                return;
            }
            if (result == -1) {
                LOG.debugf("CAS create conflict for %s, rebasing", key.key());
            } else if (result == 0) {
                LOG.debugf("CAS version mismatch for %s, rebasing (attempt %d)", key.key(), attempts);
            } else {
                throw new IllegalStateException("Redis CAS returned invalid code " + result + " for " + key.key());
            }

            if (attempts < Constants.CAS_MAX_RETRIES) {
                RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.CAS_RETRY);
            }

            Map<String, String> fresh = connection.sync().hgetall(key.key());
            if (fresh == null || fresh.isEmpty()) {
                entity.rebase(null);
                loadedIndexes.put(key, Set.of());
            } else {
                entity.rebase(fresh);
                rememberLoadedIndexes(key, entity);
            }
        }
        RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.CAS_FAIL);
        throw new IllegalStateException(
                "Failed to commit entity after " + Constants.CAS_MAX_RETRIES + " CAS retries: " + key.key());
    }

    private void purgeExpired(K key, MapEntity entity) {
        A adapter = adapterSupplier.create(key, entity);
        List<RedisHashCas.IndexOp> removals = new ArrayList<>();
        for (IndexUpdate index : indexFunction.apply(adapter)) {
            if (index.member() != null) {
                removals.add(toRemoveOp(index));
            }
        }
        RedisHashCas.deleteWithIndexes(connection, key.key(), removals);
        RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.DEL);
        loadedIndexes.remove(key);
    }

    private void rememberLoadedIndexes(K key, A adapter) {
        loadedIndexes.put(key, Set.copyOf(indexFunction.apply(adapter)));
    }

    private static RedisHashCas.IndexOp toAddOp(IndexUpdate idx) {
        if (idx.score() != null) {
            return RedisHashCas.IndexOp.zadd(idx.indexKey(), idx.member(), idx.score());
        }
        return RedisHashCas.IndexOp.add(idx.indexKey(), idx.member());
    }

    private static RedisHashCas.IndexOp toRemoveOp(IndexUpdate idx) {
        if (idx.score() != null) {
            return RedisHashCas.IndexOp.zrem(idx.indexKey(), idx.member());
        }
        return RedisHashCas.IndexOp.remove(idx.indexKey(), idx.member());
    }

    @Override
    protected void rollbackImpl() {
        cache.clear();
        toDelete.clear();
        loadedIndexes.clear();
    }

    /**
     * Index membership. When {@code score} is non-null, the index is a ZSET (member scored by
     * lastSessionRefresh); otherwise a SET.
     */
    public record IndexUpdate(String indexKey, String member, Double score) {
        public IndexUpdate(String indexKey, String member) {
            this(indexKey, member, null);
        }

        public static IndexUpdate zset(String indexKey, String member, double score) {
            return new IndexUpdate(indexKey, member, score);
        }
    }

    private record PendingDelete<K, A>(A entity, List<IndexUpdate> indexRemovals) {}
}
