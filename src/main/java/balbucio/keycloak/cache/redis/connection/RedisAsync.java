package balbucio.keycloak.cache.redis.connection;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mode-agnostic async Redis commands used for pipelined bulk reads.
 */
public interface RedisAsync {

    void setAutoFlushCommands(boolean autoFlush);

    void flushCommands();

    CompletableFuture<Map<String, String>> hgetall(String key);
}
