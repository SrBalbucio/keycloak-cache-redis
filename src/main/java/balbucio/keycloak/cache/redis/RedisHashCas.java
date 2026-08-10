package balbucio.keycloak.cache.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;

/**
 * Optimistic concurrency control for Redis hashes via a Lua CAS script.
 *
 * <p>Returns {@code 1} on success, {@code 0} on version mismatch, {@code -1} on create conflict,
 * {@code -2} on invalid arguments.
 */
public final class RedisHashCas {

    public static final String SCRIPT =
            """
            local key = KEYS[1]
            local expected = ARGV[1]
            local expireAt = tonumber(ARGV[2])
            local nSet = tonumber(ARGV[3])
            local nDel = tonumber(ARGV[4])
            if (nSet == nil) or (nDel == nil) then
              return -2
            end
            local current = redis.call('HGET', key, 'version')
            if expected == '' then
              if current ~= false then
                return -1
              end
            else
              if current == false or tostring(current) ~= tostring(expected) then
                return 0
              end
            end
            local idx = 5
            for i = 1, nSet do
              local field = ARGV[idx]
              local value = ARGV[idx + 1]
              idx = idx + 2
              redis.call('HSET', key, field, value)
            end
            for i = 1, nDel do
              local field = ARGV[idx]
              idx = idx + 1
              redis.call('HDEL', key, field)
            end
            redis.call('HINCRBY', key, 'version', 1)
            if expireAt ~= nil and expireAt > 0 then
              redis.call('PEXPIREAT', key, expireAt)
            end
            return 1
            """;

    /**
     * CAS + logical ops (HINCRBY) in one round-trip. ARGV layout after nDel fields:
     * nIncr, then (field, delta)*nIncr.
     */
    public static final String SCRIPT_WITH_OPS =
            """
            local key = KEYS[1]
            local expected = ARGV[1]
            local expireAt = tonumber(ARGV[2])
            local nSet = tonumber(ARGV[3])
            local nDel = tonumber(ARGV[4])
            if (nSet == nil) or (nDel == nil) then
              return -2
            end
            local current = redis.call('HGET', key, 'version')
            if expected == '' then
              if current ~= false then
                return -1
              end
            else
              if current == false or tostring(current) ~= tostring(expected) then
                return 0
              end
            end
            local idx = 5
            for i = 1, nSet do
              local field = ARGV[idx]
              local value = ARGV[idx + 1]
              idx = idx + 2
              redis.call('HSET', key, field, value)
            end
            for i = 1, nDel do
              local field = ARGV[idx]
              idx = idx + 1
              redis.call('HDEL', key, field)
            end
            local nIncr = tonumber(ARGV[idx])
            idx = idx + 1
            if nIncr == nil then
              return -2
            end
            for i = 1, nIncr do
              local field = ARGV[idx]
              local delta = tonumber(ARGV[idx + 1])
              idx = idx + 2
              redis.call('HINCRBY', key, field, delta)
            end
            redis.call('HINCRBY', key, 'version', 1)
            if expireAt ~= nil and expireAt > 0 then
              redis.call('PEXPIREAT', key, expireAt)
            end
            return 1
            """;

    /**
     * CAS + optional increments + index SADD/SREM + index PEXPIREAT in one atomic script.
     *
     * <p>KEYS[1] = entity hash; KEYS[2..] = distinct index keys referenced by ops.
     * ARGV after the CAS/incr section: nIdxOps, then for each op: (op, keyIndex, member) where
     * op is {@code 1}=SADD or {@code 0}=SREM and keyIndex is 1-based into KEYS.
     */
    public static final String SCRIPT_WITH_INDEXES =
            """
            local key = KEYS[1]
            local expected = ARGV[1]
            local expireAt = tonumber(ARGV[2])
            local nSet = tonumber(ARGV[3])
            local nDel = tonumber(ARGV[4])
            if (nSet == nil) or (nDel == nil) then
              return -2
            end
            local current = redis.call('HGET', key, 'version')
            if expected == '' then
              if current ~= false then
                return -1
              end
            else
              if current == false or tostring(current) ~= tostring(expected) then
                return 0
              end
            end
            local idx = 5
            for i = 1, nSet do
              local field = ARGV[idx]
              local value = ARGV[idx + 1]
              idx = idx + 2
              redis.call('HSET', key, field, value)
            end
            for i = 1, nDel do
              local field = ARGV[idx]
              idx = idx + 1
              redis.call('HDEL', key, field)
            end
            local nIncr = tonumber(ARGV[idx])
            idx = idx + 1
            if nIncr == nil then
              return -2
            end
            for i = 1, nIncr do
              local field = ARGV[idx]
              local delta = tonumber(ARGV[idx + 1])
              idx = idx + 2
              redis.call('HINCRBY', key, field, delta)
            end
            redis.call('HINCRBY', key, 'version', 1)
            if expireAt ~= nil and expireAt > 0 then
              redis.call('PEXPIREAT', key, expireAt)
            end
            local nIdx = tonumber(ARGV[idx])
            idx = idx + 1
            if nIdx == nil then
              return -2
            end
            for i = 1, nIdx do
              local op = tonumber(ARGV[idx])
              local keyIndex = tonumber(ARGV[idx + 1])
              local member = ARGV[idx + 2]
              idx = idx + 3
              local indexKey = KEYS[keyIndex]
              -- op: 1=SADD, 0=SREM, 2=ZADD(+score), 3=ZREM
              if op == 1 then
                redis.call('SADD', indexKey, member)
                if expireAt ~= nil and expireAt > 0 then
                  local ttl = redis.call('PTTL', indexKey)
                  local now = tonumber(redis.call('TIME')[1]) * 1000
                  local desired = expireAt - now
                  if ttl < 0 or (desired > 0 and ttl < desired) then
                    redis.call('PEXPIREAT', indexKey, expireAt)
                  end
                end
              elseif op == 0 then
                redis.call('SREM', indexKey, member)
              elseif op == 2 then
                local score = tonumber(ARGV[idx])
                idx = idx + 1
                redis.call('ZADD', indexKey, score, member)
                if expireAt ~= nil and expireAt > 0 then
                  local ttl = redis.call('PTTL', indexKey)
                  local now = tonumber(redis.call('TIME')[1]) * 1000
                  local desired = expireAt - now
                  if ttl < 0 or (desired > 0 and ttl < desired) then
                    redis.call('PEXPIREAT', indexKey, expireAt)
                  end
                end
              elseif op == 3 then
                redis.call('ZREM', indexKey, member)
              end
            end
            return 1
            """;

    /** Delete entity hash and remove members from SET/ZSET index keys atomically. */
    public static final String SCRIPT_DELETE_WITH_INDEXES =
            """
            local key = KEYS[1]
            redis.call('DEL', key)
            local nIdx = tonumber(ARGV[1])
            local idx = 2
            for i = 1, nIdx do
              local keyIndex = tonumber(ARGV[idx])
              local member = ARGV[idx + 1]
              local kind = tonumber(ARGV[idx + 2])
              idx = idx + 3
              if kind == 3 then
                redis.call('ZREM', KEYS[keyIndex], member)
              else
                redis.call('SREM', KEYS[keyIndex], member)
              end
            end
            return 1
            """;

    private RedisHashCas() {}

    public static String load(RedisSync sync) {
        return sync.scriptLoad(SCRIPT);
    }

    /**
     * Apply a CAS update. {@code expectedVersion} null means create (hash must not exist).
     */
    public static long hsetex(
            RedisConnectionProvider connection,
            String key,
            Long expectedVersion,
            long expireAtMillis,
            Map<String, String> toSet,
            Collection<String> toDelete) {
        return hsetex(connection, key, expectedVersion, expireAtMillis, toSet, toDelete, Map.of());
    }

    /**
     * CAS update with additional logical increments ({@code field -> delta}).
     */
    public static long hsetex(
            RedisConnectionProvider connection,
            String key,
            Long expectedVersion,
            long expireAtMillis,
            Map<String, String> toSet,
            Collection<String> toDelete,
            Map<String, Long> increments) {
        return hsetex(
                connection,
                key,
                expectedVersion,
                expireAtMillis,
                toSet,
                toDelete,
                increments,
                List.of(),
                List.of());
    }

    /**
     * CAS update with index membership deltas. When index keys share a hash slot with {@code key}
     * (standalone/sentinel always; cluster when hash-tagged), the whole update is atomic. On Redis
     * Cluster {@code CROSSSLOT}, falls back to CAS-only then best-effort index updates.
     */
    public static long hsetex(
            RedisConnectionProvider connection,
            String key,
            Long expectedVersion,
            long expireAtMillis,
            Map<String, String> toSet,
            Collection<String> toDelete,
            Map<String, Long> increments,
            Collection<IndexOp> indexAdds,
            Collection<IndexOp> indexRemoves) {

        Map<String, Long> incr = increments == null ? Map.of() : increments;
        List<IndexOp> adds = indexAdds == null ? List.of() : List.copyOf(indexAdds);
        List<IndexOp> removes = indexRemoves == null ? List.of() : List.copyOf(indexRemoves);

        if (adds.isEmpty() && removes.isEmpty()) {
            return hsetexPlain(connection, key, expectedVersion, expireAtMillis, toSet, toDelete, incr);
        }

        List<String> keys = new ArrayList<>();
        keys.add(key);
        Map<String, Integer> keyIndex = new java.util.LinkedHashMap<>();
        keyIndex.put(key, 1);

        List<String> args = new ArrayList<>();
        args.add(expectedVersion == null ? "" : Long.toString(expectedVersion));
        args.add(Long.toString(expireAtMillis));
        args.add(Integer.toString(toSet.size()));
        args.add(Integer.toString(toDelete.size()));
        for (Map.Entry<String, String> e : toSet.entrySet()) {
            args.add(e.getKey());
            args.add(e.getValue() == null ? Constants.NULL_SENTINEL : e.getValue());
        }
        for (String field : toDelete) {
            args.add(field);
        }
        args.add(Integer.toString(incr.size()));
        for (Map.Entry<String, Long> e : incr.entrySet()) {
            args.add(e.getKey());
            args.add(Long.toString(e.getValue()));
        }

        List<IndexOp> allOps = new ArrayList<>(removes.size() + adds.size());
        allOps.addAll(removes);
        allOps.addAll(adds);
        args.add(Integer.toString(allOps.size()));
        for (IndexOp op : allOps) {
            int ki = keyIndex.computeIfAbsent(op.indexKey(), k -> {
                keys.add(k);
                return keys.size();
            });
            args.add(Integer.toString(op.kind().code()));
            args.add(Integer.toString(ki));
            args.add(op.member());
            if (op.kind() == IndexOp.Kind.ZADD) {
                args.add(Double.toString(op.score() == null ? 0d : op.score()));
            }
        }

        RedisSync sync = connection.sync();
        String[] keyArr = keys.toArray(new String[0]);
        String[] argv = args.toArray(new String[0]);
        try {
            Long result =
                    sync.eval(SCRIPT_WITH_INDEXES, ScriptOutputType.INTEGER, keyArr, argv);
            return result == null ? -2 : result;
        } catch (RedisCommandExecutionException ex) {
            if (!isCrossSlot(ex)) {
                throw ex;
            }
            // Cluster: entity and indexes on different slots — CAS then best-effort indexes.
            long result =
                    hsetexPlain(connection, key, expectedVersion, expireAtMillis, toSet, toDelete, incr);
            if (result == 1) {
                applyIndexesBestEffort(sync, expireAtMillis, removes, adds);
            }
            return result;
        }
    }

    /** Atomically delete a hash and SREM its index memberships when slots allow. */
    public static void deleteWithIndexes(
            RedisConnectionProvider connection, String key, Collection<IndexOp> indexRemoves) {
        List<IndexOp> removes =
                indexRemoves == null
                        ? List.of()
                        : indexRemoves.stream().filter(op -> op.member() != null).toList();
        if (removes.isEmpty()) {
            connection.sync().del(key);
            return;
        }

        List<String> keys = new ArrayList<>();
        keys.add(key);
        Map<String, Integer> keyIndex = new java.util.LinkedHashMap<>();
        keyIndex.put(key, 1);
        List<String> args = new ArrayList<>();
        args.add(Integer.toString(removes.size()));
        for (IndexOp op : removes) {
            int ki = keyIndex.computeIfAbsent(op.indexKey(), k -> {
                keys.add(k);
                return keys.size();
            });
            args.add(Integer.toString(ki));
            args.add(op.member());
            args.add(Integer.toString(op.kind() == IndexOp.Kind.ZREM ? 3 : 0));
        }

        RedisSync sync = connection.sync();
        try {
            sync.eval(
                    SCRIPT_DELETE_WITH_INDEXES,
                    ScriptOutputType.INTEGER,
                    keys.toArray(new String[0]),
                    args.toArray(new String[0]));
        } catch (RedisCommandExecutionException ex) {
            if (!isCrossSlot(ex)) {
                throw ex;
            }
            sync.del(key);
            applyIndexesBestEffort(sync, 0L, removes, List.of());
        }
    }

    private static long hsetexPlain(
            RedisConnectionProvider connection,
            String key,
            Long expectedVersion,
            long expireAtMillis,
            Map<String, String> toSet,
            Collection<String> toDelete,
            Map<String, Long> increments) {

        boolean hasIncr = increments != null && !increments.isEmpty();
        List<String> args = new ArrayList<>();
        args.add(expectedVersion == null ? "" : Long.toString(expectedVersion));
        args.add(Long.toString(expireAtMillis));
        args.add(Integer.toString(toSet.size()));
        args.add(Integer.toString(toDelete.size()));
        for (Map.Entry<String, String> e : toSet.entrySet()) {
            args.add(e.getKey());
            args.add(e.getValue() == null ? Constants.NULL_SENTINEL : e.getValue());
        }
        for (String field : toDelete) {
            args.add(field);
        }

        String script;
        if (hasIncr) {
            args.add(Integer.toString(increments.size()));
            for (Map.Entry<String, Long> e : increments.entrySet()) {
                args.add(e.getKey());
                args.add(Long.toString(e.getValue()));
            }
            script = SCRIPT_WITH_OPS;
        } else {
            script = SCRIPT;
        }

        RedisSync sync = connection.sync();
        String sha = hasIncr ? null : connection.casScriptSha();
        String[] argv = args.toArray(new String[0]);
        try {
            if (sha != null) {
                Long result = sync.evalsha(sha, ScriptOutputType.INTEGER, new String[] {key}, argv);
                return result == null ? -2 : result;
            }
        } catch (RedisNoScriptException noscript) {
            // Sentinel failover / SCRIPT FLUSH — fall through to EVAL
        }
        Long result = sync.eval(script, ScriptOutputType.INTEGER, new String[] {key}, argv);
        return result == null ? -2 : result;
    }

    private static void applyIndexesBestEffort(
            RedisSync sync, long expireAtMillis, Collection<IndexOp> removes, Collection<IndexOp> adds) {
        for (IndexOp op : removes) {
            if (op.member() == null) {
                continue;
            }
            if (op.kind() == IndexOp.Kind.ZREM || op.kind() == IndexOp.Kind.ZADD) {
                sync.zrem(op.indexKey(), op.member());
            } else {
                sync.srem(op.indexKey(), op.member());
                RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.SREM);
            }
        }
        for (IndexOp op : adds) {
            if (op.member() == null) {
                continue;
            }
            if (op.kind() == IndexOp.Kind.ZADD) {
                sync.zadd(op.indexKey(), op.score() == null ? 0d : op.score(), op.member());
                refreshIndexTtl(sync, op.indexKey(), expireAtMillis);
            } else {
                sync.sadd(op.indexKey(), op.member());
                RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.SADD);
                refreshIndexTtl(sync, op.indexKey(), expireAtMillis);
            }
        }
    }

    static void refreshIndexTtl(RedisSync sync, String indexKey, long expireAtMillis) {
        if (expireAtMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = expireAtMillis - now;
        if (remaining <= 0) {
            return;
        }
        Long pttl = sync.pttl(indexKey);
        if (pttl == null || pttl < remaining) {
            sync.pexpire(indexKey, remaining);
        }
    }

    private static boolean isCrossSlot(RuntimeException ex) {
        String msg = ex.getMessage();
        return msg != null && (msg.contains("CROSSSLOT") || msg.contains("crossslot"));
    }

    /** Index membership mutation applied with CAS or delete. */
    public record IndexOp(String indexKey, String member, Kind kind, Double score) {
        public enum Kind {
            SREM(0),
            SADD(1),
            ZADD(2),
            ZREM(3);

            private final int code;

            Kind(int code) {
                this.code = code;
            }

            int code() {
                return code;
            }
        }

        public static IndexOp add(String indexKey, String member) {
            return new IndexOp(indexKey, member, Kind.SADD, null);
        }

        public static IndexOp remove(String indexKey, String member) {
            return new IndexOp(indexKey, member, Kind.SREM, null);
        }

        public static IndexOp zadd(String indexKey, String member, double score) {
            return new IndexOp(indexKey, member, Kind.ZADD, score);
        }

        public static IndexOp zrem(String indexKey, String member) {
            return new IndexOp(indexKey, member, Kind.ZREM, null);
        }

        /** @deprecated use {@link #kind()} */
        public boolean add() {
            return kind == Kind.SADD || kind == Kind.ZADD;
        }
    }

    /** Build unique index key list for KEYS arrays. */
    public static List<String> distinctIndexKeys(Collection<IndexOp> ops) {
        Set<String> keys = new LinkedHashSet<>();
        if (ops != null) {
            for (IndexOp op : ops) {
                if (op != null && op.indexKey() != null) {
                    keys.add(op.indexKey());
                }
            }
        }
        return List.copyOf(keys);
    }
}
