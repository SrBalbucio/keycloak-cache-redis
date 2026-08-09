package balbucio.keycloak.cache.redis.connection;

import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.keycloak.provider.Provider;

public interface RedisConnectionProvider extends Provider {

    /** Shared synchronous command facade (standalone/sentinel/cluster). */
    RedisSync sync();

    /** Shared async command facade for pipelining. */
    RedisAsync async();

    /**
     * Opens a dedicated Pub/Sub connection. Caller owns the lifecycle and must {@code close()} it.
     * Never reuse the shared command connection for subscribe.
     */
    StatefulRedisPubSubConnection<String, String> connectPubSub();

    RedisMode mode();

    /** Global key prefix applied to all Redis keys (may be empty). */
    String keyPrefix();

    /** SHA1 of the CAS Lua script loaded via SCRIPT LOAD, or null if not loaded. */
    String casScriptSha();
}
