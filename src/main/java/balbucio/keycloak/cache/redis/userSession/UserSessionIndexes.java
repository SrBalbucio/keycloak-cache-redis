package balbucio.keycloak.cache.redis.userSession;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public final class UserSessionIndexes {

    private UserSessionIndexes() {}

    public static String userIndex(String userId, boolean offline) {
        return RedisKeySpace.key(relativePrefix(offline) + "user-index:" + userId);
    }

    public static String realmIndex(String realmId, boolean offline) {
        return RedisKeySpace.key(relativePrefix(offline) + "realm-index:" + realmId);
    }

    public static String brokerSessionIndex(String brokerSessionId, boolean offline) {
        return RedisKeySpace.key(relativePrefix(offline) + "broker-session-index:" + brokerSessionId);
    }

    public static String brokerUserIndex(String brokerUserId, boolean offline) {
        return RedisKeySpace.key(relativePrefix(offline) + "broker-user-index:" + brokerUserId);
    }

    public static String correspondingSessionIndex(String correspondingId, boolean offline) {
        return RedisKeySpace.key(relativePrefix(offline) + "corresponding-session-index:" + correspondingId);
    }

    public static String clientIndex(String clientId, boolean offline) {
        String base = offline ? "authenticated-client-offline:" : "authenticated-client:";
        return RedisKeySpace.key(base + "client-index:" + clientId);
    }

    private static String relativePrefix(boolean offline) {
        return offline ? "user-session-offline:" : "user-session:";
    }
}
