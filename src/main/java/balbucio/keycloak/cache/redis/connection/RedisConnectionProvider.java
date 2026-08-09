package balbucio.keycloak.cache.redis.connection;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.keycloak.provider.Provider;

public interface RedisConnectionProvider extends Provider {

    RedisCommands<String, String> sync();

    RedisAsyncCommands<String, String> async();

    StatefulRedisPubSubConnection<String, String> pubSub();

    RedisMode mode();

    /** Global key prefix applied to all Redis keys (may be empty). */
    String keyPrefix();

    /** SHA1 of the CAS Lua script loaded via SCRIPT LOAD, or null if not loaded. */
    String casScriptSha();
}
