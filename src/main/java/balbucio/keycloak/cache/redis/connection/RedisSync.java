package balbucio.keycloak.cache.redis.connection;

import java.util.Map;
import java.util.Set;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TransactionResult;

/**
 * Mode-agnostic synchronous Redis commands used by this extension.
 *
 * <p>Standalone/sentinel support {@code MULTI/EXEC}; cluster mode does not
 * ({@link #supportsTransactions()} returns {@code false}).
 */
public interface RedisSync {

    boolean supportsTransactions();

    Map<String, String> hgetall(String key);

    String hget(String key, String field);

    Boolean hset(String key, String field, String value);

    Long hset(String key, Map<String, String> map);

    Long del(String... keys);

    Long exists(String... keys);

    Long incr(String key);

    Long sadd(String key, String... members);

    Long srem(String key, String... members);

    Set<String> smembers(String key);

    String get(String key);

    String set(String key, String value);

    String set(String key, String value, SetArgs args);

    Boolean pexpire(String key, long milliseconds);

    Long pttl(String key);

    Long publish(String channel, String message);

    String scriptLoad(String script);

    <T> T eval(String script, ScriptOutputType type, String[] keys, String... values);

    <T> T evalsha(String digest, ScriptOutputType type, String[] keys, String... values);

    String multi();

    TransactionResult exec();

    String discard();

    String ping();

    String flushdb();
}
