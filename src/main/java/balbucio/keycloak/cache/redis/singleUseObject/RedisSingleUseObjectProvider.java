package balbucio.keycloak.cache.redis.singleUseObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import io.lettuce.core.ScriptOutputType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Immediate Redis-backed single-use store. {@link #remove} is atomic (GET+DEL via Lua).
 */
public class RedisSingleUseObjectProvider implements SingleUseObjectProvider {

    private static final String NOTES_PREFIX = "n.";

    /**
     * Atomically returns all hash fields and deletes the key. Returns flat field/value list or empty.
     */
    private static final String REMOVE_SCRIPT =
            """
            local exists = redis.call('EXISTS', KEYS[1])
            if exists == 0 then
              return {}
            end
            local data = redis.call('HGETALL', KEYS[1])
            redis.call('DEL', KEYS[1])
            return data
            """;

    private static final String PUT_IF_ABSENT_SCRIPT =
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            local ttl = tonumber(ARGV[1])
            local n = tonumber(ARGV[2])
            local idx = 3
            for i = 1, n do
              redis.call('HSET', KEYS[1], ARGV[idx], ARGV[idx + 1])
              idx = idx + 2
            end
            if ttl ~= nil and ttl > 0 then
              redis.call('PEXPIRE', KEYS[1], ttl)
            end
            return 1
            """;

    /** Replace key contents and set TTL in one round-trip (DEL+HSET+PEXPIRE). */
    private static final String PUT_SCRIPT =
            """
            redis.call('DEL', KEYS[1])
            local ttl = tonumber(ARGV[1])
            local n = tonumber(ARGV[2])
            local idx = 3
            for i = 1, n do
              redis.call('HSET', KEYS[1], ARGV[idx], ARGV[idx + 1])
              idx = idx + 2
            end
            if ttl ~= nil and ttl > 0 then
              redis.call('PEXPIRE', KEYS[1], ttl)
            end
            return 1
            """;

    /**
     * Atomically replace notes while preserving remaining TTL. Returns 0 if key missing, 1 on
     * success.
     */
    private static final String REPLACE_SCRIPT =
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local ttl = redis.call('PTTL', KEYS[1])
            redis.call('DEL', KEYS[1])
            local n = tonumber(ARGV[1])
            local idx = 2
            for i = 1, n do
              redis.call('HSET', KEYS[1], ARGV[idx], ARGV[idx + 1])
              idx = idx + 2
            end
            if ttl ~= nil and ttl > 0 then
              redis.call('PEXPIRE', KEYS[1], ttl)
            end
            return 1
            """;

    private final RedisConnectionProvider connection;

    public RedisSingleUseObjectProvider(KeycloakSession session) {
        this.connection = session.getProvider(RedisConnectionProvider.class);
    }

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
        Objects.requireNonNull(key);
        if (lifespanSeconds <= 0) {
            throw new IllegalArgumentException("lifespanSeconds must be positive");
        }
        if (key.endsWith(REVOKED_KEY) && notes != null && !notes.isEmpty()) {
            throw new ModelException("Notes are not supported for revoked tokens");
        }
        List<String> args = hashArgs(lifespanSeconds * 1000L, notes);
        connection
                .sync()
                .eval(PUT_SCRIPT, ScriptOutputType.INTEGER, new String[] {redisKey(key)}, args.toArray(new String[0]));
        RedisMetrics.record(RedisMetrics.Cache.SINGLE_USE, RedisMetrics.Op.EVAL);
    }

    @Override
    public Map<String, String> get(String key) {
        Objects.requireNonNull(key);
        if (key.endsWith(REVOKED_KEY)) {
            throw new ModelException("Revoked tokens can't be retrieved");
        }
        Map<String, String> hash = connection.sync().hgetall(redisKey(key));
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        return fromHash(hash);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> remove(String key) {
        Objects.requireNonNull(key);
        if (key.endsWith(REVOKED_KEY)) {
            throw new ModelException("Revoked tokens can't be removed");
        }
        Object raw =
                connection.sync().eval(REMOVE_SCRIPT, ScriptOutputType.MULTI, new String[] {redisKey(key)});
        RedisMetrics.record(RedisMetrics.Cache.SINGLE_USE, RedisMetrics.Op.EVAL);
        if (raw == null) {
            return null;
        }
        List<String> flat;
        if (raw instanceof List<?> list) {
            flat = (List<String>) list;
        } else {
            return null;
        }
        if (flat.isEmpty()) {
            return null;
        }
        Map<String, String> hash = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            hash.put(flat.get(i), flat.get(i + 1));
        }
        return fromHash(hash);
    }

    @Override
    public boolean replace(String key, Map<String, String> notes) {
        Objects.requireNonNull(key);
        if (key.endsWith(REVOKED_KEY)) {
            throw new ModelException("Revoked tokens can't be replaced");
        }
        List<String> args = hashArgs(null, notes);
        Long result =
                connection
                        .sync()
                        .eval(
                                REPLACE_SCRIPT,
                                ScriptOutputType.INTEGER,
                                new String[] {redisKey(key)},
                                args.toArray(new String[0]));
        RedisMetrics.record(RedisMetrics.Cache.SINGLE_USE, RedisMetrics.Op.EVAL);
        return result != null && result == 1L;
    }

    @Override
    public boolean putIfAbsent(String key, long lifespanInSeconds) {
        Objects.requireNonNull(key);
        if (lifespanInSeconds <= 0) {
            throw new IllegalArgumentException("lifespanInSeconds must be positive");
        }
        List<String> args = new ArrayList<>();
        args.add(Long.toString(lifespanInSeconds * 1000L));
        args.add("1");
        args.add(Constants.VERSION_FIELD);
        args.add("1");
        Long result =
                connection
                        .sync()
                        .eval(
                                PUT_IF_ABSENT_SCRIPT,
                                ScriptOutputType.INTEGER,
                                new String[] {redisKey(key)},
                                args.toArray(new String[0]));
        return result != null && result == 1L;
    }

    @Override
    public boolean contains(String key) {
        Objects.requireNonNull(key);
        Long exists = connection.sync().exists(redisKey(key));
        return exists != null && exists > 0;
    }

    @Override
    public void close() {}

    private static String redisKey(String logicalKey) {
        return SingleUseObjectKey.of(logicalKey).key();
    }

    /**
     * Build Lua ARGV for put/replace: optional leading TTL millis, then n, then field/value pairs.
     * When {@code ttlMillis} is null, only {@code n} + pairs are emitted (replace script).
     */
    private static List<String> hashArgs(Long ttlMillis, Map<String, String> notes) {
        Map<String, String> hash = toHash(notes);
        if (hash.isEmpty()) {
            hash = Map.of(Constants.VERSION_FIELD, "1");
        }
        List<String> args = new ArrayList<>();
        if (ttlMillis != null) {
            args.add(Long.toString(ttlMillis));
        }
        args.add(Integer.toString(hash.size()));
        for (Map.Entry<String, String> e : hash.entrySet()) {
            args.add(e.getKey());
            args.add(e.getValue());
        }
        return args;
    }

    private static Map<String, String> toHash(Map<String, String> notes) {
        if (notes == null || notes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> hash = new HashMap<>();
        for (Map.Entry<String, String> e : notes.entrySet()) {
            hash.put(NOTES_PREFIX + e.getKey(), e.getValue() == null ? Constants.NULL_SENTINEL : e.getValue());
        }
        return hash;
    }

    private static Map<String, String> fromHash(Map<String, String> hash) {
        Map<String, String> notes = new HashMap<>();
        for (Map.Entry<String, String> e : hash.entrySet()) {
            if (e.getKey().startsWith(NOTES_PREFIX)) {
                String value = e.getValue();
                if (Constants.NULL_SENTINEL.equals(value)) {
                    notes.put(e.getKey().substring(NOTES_PREFIX.length()), null);
                } else {
                    notes.put(e.getKey().substring(NOTES_PREFIX.length()), value);
                }
            }
        }
        return notes;
    }
}
