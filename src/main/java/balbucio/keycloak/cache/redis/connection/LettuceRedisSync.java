package balbucio.keycloak.cache.redis.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.lettuce.core.KeyValue;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;

public final class LettuceRedisSync implements RedisSync {

    private final boolean transactions;
    private final Delegate delegate;

    private LettuceRedisSync(boolean transactions, Delegate delegate) {
        this.transactions = transactions;
        this.delegate = delegate;
    }

    public static LettuceRedisSync of(RedisCommands<String, String> commands) {
        return new LettuceRedisSync(true, new CommandsDelegate(commands));
    }

    public static LettuceRedisSync of(RedisAdvancedClusterCommands<String, String> commands) {
        return new LettuceRedisSync(false, new ClusterDelegate(commands));
    }

    @Override
    public boolean supportsTransactions() {
        return transactions;
    }

    @Override
    public Map<String, String> hgetall(String key) {
        return delegate.hgetall(key);
    }

    @Override
    public String hget(String key, String field) {
        return delegate.hget(key, field);
    }

    @Override
    public Boolean hset(String key, String field, String value) {
        return delegate.hset(key, field, value);
    }

    @Override
    public Long hset(String key, Map<String, String> map) {
        return delegate.hset(key, map);
    }

    @Override
    public Long del(String... keys) {
        return delegate.del(keys);
    }

    @Override
    public Long exists(String... keys) {
        return delegate.exists(keys);
    }

    @Override
    public Long incr(String key) {
        return delegate.incr(key);
    }

    @Override
    public Long incrby(String key, long amount) {
        return delegate.incrby(key, amount);
    }

    @Override
    public Long sadd(String key, String... members) {
        return delegate.sadd(key, members);
    }

    @Override
    public Long srem(String key, String... members) {
        return delegate.srem(key, members);
    }

    @Override
    public Set<String> smembers(String key) {
        return delegate.smembers(key);
    }

    @Override
    public Long scard(String key) {
        return delegate.scard(key);
    }

    @Override
    public Long zadd(String key, double score, String member) {
        return delegate.zadd(key, score, member);
    }

    @Override
    public Long zrem(String key, String... members) {
        return delegate.zrem(key, members);
    }

    @Override
    public List<String> zrevrange(String key, long start, long stop) {
        return delegate.zrevrange(key, start, stop);
    }

    @Override
    public Long zcard(String key) {
        return delegate.zcard(key);
    }

    @Override
    public String get(String key) {
        return delegate.get(key);
    }

    @Override
    public List<String> mget(String... keys) {
        return delegate.mget(keys);
    }

    @Override
    public String set(String key, String value) {
        return delegate.set(key, value);
    }

    @Override
    public String set(String key, String value, SetArgs args) {
        return delegate.set(key, value, args);
    }

    @Override
    public Boolean pexpire(String key, long milliseconds) {
        return delegate.pexpire(key, milliseconds);
    }

    @Override
    public Long pttl(String key) {
        return delegate.pttl(key);
    }

    @Override
    public Long publish(String channel, String message) {
        return delegate.publish(channel, message);
    }

    @Override
    public String scriptLoad(String script) {
        return delegate.scriptLoad(script);
    }

    @Override
    public <T> T eval(String script, ScriptOutputType type, String[] keys, String... values) {
        return delegate.eval(script, type, keys, values);
    }

    @Override
    public <T> T evalsha(String digest, ScriptOutputType type, String[] keys, String... values) {
        return delegate.evalsha(digest, type, keys, values);
    }

    @Override
    public String multi() {
        if (!transactions) {
            throw new UnsupportedOperationException("MULTI is not supported in Redis cluster mode");
        }
        return delegate.multi();
    }

    @Override
    public TransactionResult exec() {
        if (!transactions) {
            throw new UnsupportedOperationException("EXEC is not supported in Redis cluster mode");
        }
        return delegate.exec();
    }

    @Override
    public String discard() {
        if (!transactions) {
            throw new UnsupportedOperationException("DISCARD is not supported in Redis cluster mode");
        }
        return delegate.discard();
    }

    @Override
    public String ping() {
        return delegate.ping();
    }

    @Override
    public String flushdb() {
        return delegate.flushdb();
    }

    private interface Delegate {
        Map<String, String> hgetall(String key);

        String hget(String key, String field);

        Boolean hset(String key, String field, String value);

        Long hset(String key, Map<String, String> map);

        Long del(String... keys);

        Long exists(String... keys);

        Long incr(String key);

        Long incrby(String key, long amount);

        Long sadd(String key, String... members);

        Long srem(String key, String... members);

        Set<String> smembers(String key);

        Long scard(String key);

        Long zadd(String key, double score, String member);

        Long zrem(String key, String... members);

        List<String> zrevrange(String key, long start, long stop);

        Long zcard(String key);

        String get(String key);

        List<String> mget(String... keys);

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

    private static final class CommandsDelegate implements Delegate {
        private final RedisCommands<String, String> c;

        CommandsDelegate(RedisCommands<String, String> c) {
            this.c = c;
        }

        @Override
        public Map<String, String> hgetall(String key) {
            return c.hgetall(key);
        }

        @Override
        public String hget(String key, String field) {
            return c.hget(key, field);
        }

        @Override
        public Boolean hset(String key, String field, String value) {
            return c.hset(key, field, value);
        }

        @Override
        public Long hset(String key, Map<String, String> map) {
            return c.hset(key, map);
        }

        @Override
        public Long del(String... keys) {
            return c.del(keys);
        }

        @Override
        public Long exists(String... keys) {
            return c.exists(keys);
        }

        @Override
        public Long incr(String key) {
            return c.incr(key);
        }

        @Override
        public Long incrby(String key, long amount) {
            return c.incrby(key, amount);
        }

        @Override
        public Long scard(String key) {
            return c.scard(key);
        }

        @Override
        public Long zadd(String key, double score, String member) {
            return c.zadd(key, score, member);
        }

        @Override
        public Long zrem(String key, String... members) {
            return c.zrem(key, members);
        }

        @Override
        public List<String> zrevrange(String key, long start, long stop) {
            List<String> out = c.zrevrange(key, start, stop);
            return out == null ? List.of() : out;
        }

        @Override
        public Long zcard(String key) {
            return c.zcard(key);
        }

        @Override
        public List<String> mget(String... keys) {
            if (keys == null || keys.length == 0) {
                return List.of();
            }
            List<KeyValue<String, String>> raw = c.mget(keys);
            List<String> out = new ArrayList<>(keys.length);
            if (raw == null) {
                for (int i = 0; i < keys.length; i++) {
                    out.add(null);
                }
                return out;
            }
            for (KeyValue<String, String> kv : raw) {
                out.add(kv == null || !kv.hasValue() ? null : kv.getValue());
            }
            return out;
        }

        @Override
        public Long sadd(String key, String... members) {
            return c.sadd(key, members);
        }

        @Override
        public Long srem(String key, String... members) {
            return c.srem(key, members);
        }

        @Override
        public Set<String> smembers(String key) {
            return c.smembers(key);
        }

        @Override
        public String get(String key) {
            return c.get(key);
        }

        @Override
        public String set(String key, String value) {
            return c.set(key, value);
        }

        @Override
        public String set(String key, String value, SetArgs args) {
            return c.set(key, value, args);
        }

        @Override
        public Boolean pexpire(String key, long milliseconds) {
            return c.pexpire(key, milliseconds);
        }

        @Override
        public Long pttl(String key) {
            return c.pttl(key);
        }

        @Override
        public Long publish(String channel, String message) {
            return c.publish(channel, message);
        }

        @Override
        public String scriptLoad(String script) {
            return c.scriptLoad(script);
        }

        @Override
        public <T> T eval(String script, ScriptOutputType type, String[] keys, String... values) {
            return c.eval(script, type, keys, values);
        }

        @Override
        public <T> T evalsha(String digest, ScriptOutputType type, String[] keys, String... values) {
            return c.evalsha(digest, type, keys, values);
        }

        @Override
        public String multi() {
            return c.multi();
        }

        @Override
        public TransactionResult exec() {
            return c.exec();
        }

        @Override
        public String discard() {
            return c.discard();
        }

        @Override
        public String ping() {
            return c.ping();
        }

        @Override
        public String flushdb() {
            return c.flushdb();
        }
    }

    private static final class ClusterDelegate implements Delegate {
        private final RedisAdvancedClusterCommands<String, String> c;

        ClusterDelegate(RedisAdvancedClusterCommands<String, String> c) {
            this.c = c;
        }

        @Override
        public Map<String, String> hgetall(String key) {
            return c.hgetall(key);
        }

        @Override
        public String hget(String key, String field) {
            return c.hget(key, field);
        }

        @Override
        public Boolean hset(String key, String field, String value) {
            return c.hset(key, field, value);
        }

        @Override
        public Long hset(String key, Map<String, String> map) {
            return c.hset(key, map);
        }

        @Override
        public Long del(String... keys) {
            return c.del(keys);
        }

        @Override
        public Long exists(String... keys) {
            return c.exists(keys);
        }

        @Override
        public Long incr(String key) {
            return c.incr(key);
        }

        @Override
        public Long incrby(String key, long amount) {
            return c.incrby(key, amount);
        }

        @Override
        public Long scard(String key) {
            return c.scard(key);
        }

        @Override
        public Long zadd(String key, double score, String member) {
            return c.zadd(key, score, member);
        }

        @Override
        public Long zrem(String key, String... members) {
            return c.zrem(key, members);
        }

        @Override
        public List<String> zrevrange(String key, long start, long stop) {
            List<String> out = c.zrevrange(key, start, stop);
            return out == null ? List.of() : out;
        }

        @Override
        public Long zcard(String key) {
            return c.zcard(key);
        }

        @Override
        public List<String> mget(String... keys) {
            if (keys == null || keys.length == 0) {
                return List.of();
            }
            List<KeyValue<String, String>> raw = c.mget(keys);
            List<String> out = new ArrayList<>(keys.length);
            if (raw == null) {
                for (int i = 0; i < keys.length; i++) {
                    out.add(null);
                }
                return out;
            }
            for (KeyValue<String, String> kv : raw) {
                out.add(kv == null || !kv.hasValue() ? null : kv.getValue());
            }
            return out;
        }

        @Override
        public Long sadd(String key, String... members) {
            return c.sadd(key, members);
        }

        @Override
        public Long srem(String key, String... members) {
            return c.srem(key, members);
        }

        @Override
        public Set<String> smembers(String key) {
            return c.smembers(key);
        }

        @Override
        public String get(String key) {
            return c.get(key);
        }

        @Override
        public String set(String key, String value) {
            return c.set(key, value);
        }

        @Override
        public String set(String key, String value, SetArgs args) {
            return c.set(key, value, args);
        }

        @Override
        public Boolean pexpire(String key, long milliseconds) {
            return c.pexpire(key, milliseconds);
        }

        @Override
        public Long pttl(String key) {
            return c.pttl(key);
        }

        @Override
        public Long publish(String channel, String message) {
            return c.publish(channel, message);
        }

        @Override
        public String scriptLoad(String script) {
            return c.scriptLoad(script);
        }

        @Override
        public <T> T eval(String script, ScriptOutputType type, String[] keys, String... values) {
            return c.eval(script, type, keys, values);
        }

        @Override
        public <T> T evalsha(String digest, ScriptOutputType type, String[] keys, String... values) {
            return c.evalsha(digest, type, keys, values);
        }

        @Override
        public String multi() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransactionResult exec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String discard() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String ping() {
            return c.ping();
        }

        @Override
        public String flushdb() {
            return c.flushdb();
        }
    }
}
