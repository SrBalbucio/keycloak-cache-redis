package balbucio.keycloak.cache.redis.userSession;

import java.util.Objects;

import balbucio.keycloak.cache.redis.Key;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public record UserSessionKey(String id, boolean offline) implements Key {

    public static final String RELATIVE_PREFIX = "user-session:";
    public static final String RELATIVE_OFFLINE_PREFIX = "user-session-offline:";

    @Override
    public String key() {
        return RedisKeySpace.key((offline ? RELATIVE_OFFLINE_PREFIX : RELATIVE_PREFIX) + id);
    }

    public static UserSessionKey of(String id, boolean offline) {
        return new UserSessionKey(Objects.requireNonNull(id), offline);
    }
}
