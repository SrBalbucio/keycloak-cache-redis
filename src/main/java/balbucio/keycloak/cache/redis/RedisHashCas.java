package balbucio.keycloak.cache.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

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

    private RedisHashCas() {}

    public static String load(RedisCommands<String, String> sync) {
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

        RedisCommands<String, String> sync = connection.sync();
        String sha = connection.casScriptSha();
        String[] argv = args.toArray(new String[0]);
        try {
            if (sha != null) {
                Long result = sync.evalsha(sha, ScriptOutputType.INTEGER, new String[] {key}, argv);
                return result == null ? -2 : result;
            }
        } catch (RedisNoScriptException noscript) {
            // Sentinel failover / SCRIPT FLUSH — fall through to EVAL
        }
        Long result = sync.eval(SCRIPT, ScriptOutputType.INTEGER, new String[] {key}, argv);
        return result == null ? -2 : result;
    }
}
