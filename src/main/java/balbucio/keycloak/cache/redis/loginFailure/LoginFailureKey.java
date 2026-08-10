package balbucio.keycloak.cache.redis.loginFailure;

import java.util.Objects;

import balbucio.keycloak.cache.redis.Key;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public record LoginFailureKey(String realmId, String userId) implements Key {

    @Override
    public String key() {
        // Hash-tag by realm so entity + realm index share a cluster slot.
        return RedisKeySpace.taggedKey(realmId, "login-failure:" + userId);
    }

    public static LoginFailureKey of(String realmId, String userId) {
        return new LoginFailureKey(Objects.requireNonNull(realmId), Objects.requireNonNull(userId));
    }
}
