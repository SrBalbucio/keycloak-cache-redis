package balbucio.keycloak.cache.redis.loginFailure;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public final class LoginFailureIndexes {

    private LoginFailureIndexes() {}

    public static String realmIndex(String realmId) {
        return RedisKeySpace.key("login-failure:realm-index:" + realmId);
    }
}
