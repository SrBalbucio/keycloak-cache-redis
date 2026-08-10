package balbucio.keycloak.cache.redis.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisKeySpaceTest {

    @AfterEach
    void reset() {
        RedisKeySpace.configure("");
    }

    @Test
    void emptyPrefixByDefault() {
        RedisKeySpace.configure(null);
        assertEquals("", RedisKeySpace.prefix());
        assertEquals("user-session:abc", RedisKeySpace.key("user-session:abc"));
    }

    @Test
    void appendsColonWhenMissing() {
        RedisKeySpace.configure("kc");
        assertEquals("kc:", RedisKeySpace.prefix());
        assertEquals("kc:user-session:1", RedisKeySpace.key("user-session:1"));
    }

    @Test
    void keepsExistingColon() {
        RedisKeySpace.configure("prod:");
        assertEquals("prod:", RedisKeySpace.prefix());
        assertEquals("prod:login-failure:r:u", RedisKeySpace.key("login-failure:r:u"));
    }

    @Test
    void taggedKeyInsertsHashTag() {
        RedisKeySpace.configure("kc");
        assertEquals("kc:{realm-1}:login-failure:user-2", RedisKeySpace.taggedKey("realm-1", "login-failure:user-2"));
        assertEquals("kc:{realm-1}:login-failure:realm-index", RedisKeySpace.taggedKey("realm-1", "login-failure:realm-index"));
    }
}
