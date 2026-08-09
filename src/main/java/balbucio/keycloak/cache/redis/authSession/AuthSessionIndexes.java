package balbucio.keycloak.cache.redis.authSession;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public final class AuthSessionIndexes {

    private AuthSessionIndexes() {}

    public static String realmIndex(String realmId) {
        return RedisKeySpace.key("auth-session:realm-index:" + realmId);
    }
}
