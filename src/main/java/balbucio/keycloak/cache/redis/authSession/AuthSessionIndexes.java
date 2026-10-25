package balbucio.keycloak.cache.redis.authSession;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public final class AuthSessionIndexes {

    private AuthSessionIndexes() {}

    public static String realmIndex(String realmId) {
        return RedisKeySpace.taggedKey(realmId, "auth-session:realm-index");
    }

    /**
     * ZSET of root authentication session ids that hold a tab authenticated as {@code userId},
     * scored by root session expiration (ms). Backs KC 26.8
     * {@code removeRootAuthenticationSessionsByAuthenticatedUser} ("sign out other devices" also
     * ends in-progress logins); expired members are pruned lazily on read.
     */
    public static String userIndex(String realmId, String userId) {
        return RedisKeySpace.taggedKey(realmId, "auth-session:by-user:" + userId);
    }
}
