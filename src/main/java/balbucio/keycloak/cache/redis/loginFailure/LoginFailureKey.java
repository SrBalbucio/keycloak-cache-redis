package balbucio.keycloak.cache.redis.loginFailure;

import java.util.Objects;

import balbucio.keycloak.cache.redis.Key;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public record LoginFailureKey(String realmId, String userId) implements Key {

    @Override
    public String key() {
        return RedisKeySpace.key("login-failure:" + realmId + ":" + userId);
    }

    public static LoginFailureKey of(String realmId, String userId) {
        return new LoginFailureKey(Objects.requireNonNull(realmId), Objects.requireNonNull(userId));
    }
}
