package balbucio.keycloak.cache.redis.connection;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;

public final class LettuceRedisAsync implements RedisAsync {

    private final AsyncDelegate delegate;

    private LettuceRedisAsync(AsyncDelegate delegate) {
        this.delegate = delegate;
    }

    public static LettuceRedisAsync of(RedisAsyncCommands<String, String> commands) {
        return new LettuceRedisAsync(new CommandsAsync(commands));
    }

    public static LettuceRedisAsync of(RedisAdvancedClusterAsyncCommands<String, String> commands) {
        return new LettuceRedisAsync(new ClusterAsync(commands));
    }

    @Override
    public void setAutoFlushCommands(boolean autoFlush) {
        delegate.setAutoFlushCommands(autoFlush);
    }

    @Override
    public void flushCommands() {
        delegate.flushCommands();
    }

    @Override
    public CompletableFuture<Map<String, String>> hgetall(String key) {
        return delegate.hgetall(key);
    }

    private interface AsyncDelegate {
        void setAutoFlushCommands(boolean autoFlush);

        void flushCommands();

        CompletableFuture<Map<String, String>> hgetall(String key);
    }

    private static final class CommandsAsync implements AsyncDelegate {
        private final RedisAsyncCommands<String, String> c;

        CommandsAsync(RedisAsyncCommands<String, String> c) {
            this.c = c;
        }

        @Override
        public void setAutoFlushCommands(boolean autoFlush) {
            c.setAutoFlushCommands(autoFlush);
        }

        @Override
        public void flushCommands() {
            c.flushCommands();
        }

        @Override
        public CompletableFuture<Map<String, String>> hgetall(String key) {
            return c.hgetall(key).toCompletableFuture();
        }
    }

    private static final class ClusterAsync implements AsyncDelegate {
        private final RedisAdvancedClusterAsyncCommands<String, String> c;

        ClusterAsync(RedisAdvancedClusterAsyncCommands<String, String> c) {
            this.c = c;
        }

        @Override
        public void setAutoFlushCommands(boolean autoFlush) {
            c.setAutoFlushCommands(autoFlush);
        }

        @Override
        public void flushCommands() {
            c.flushCommands();
        }

        @Override
        public CompletableFuture<Map<String, String>> hgetall(String key) {
            return c.hgetall(key).toCompletableFuture();
        }
    }
}
