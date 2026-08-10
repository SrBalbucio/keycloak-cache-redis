package balbucio.keycloak.cache.redis.userSession;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;

/**
 * Session index keys. All use {@code {realmId}} hash-tags so entity + indexes share a Redis Cluster
 * slot (atomic CAS + SADD/SREM / ZADD/ZREM).
 */
public final class UserSessionIndexes {

    private UserSessionIndexes() {}

    public static String userIndex(String realmId, String userId, boolean offline) {
        return RedisKeySpace.taggedKey(realmId, relativePrefix(offline) + "user-index:" + userId);
    }

    public static String realmIndex(String realmId, boolean offline) {
        return RedisKeySpace.taggedKey(realmId, relativePrefix(offline) + "realm-index");
    }

    public static String brokerSessionIndex(String realmId, String brokerSessionId, boolean offline) {
        return RedisKeySpace.taggedKey(
                realmId, relativePrefix(offline) + "broker-session-index:" + brokerSessionId);
    }

    public static String brokerUserIndex(String realmId, String brokerUserId, boolean offline) {
        return RedisKeySpace.taggedKey(
                realmId, relativePrefix(offline) + "broker-user-index:" + brokerUserId);
    }

    public static String correspondingSessionIndex(String realmId, String correspondingId, boolean offline) {
        return RedisKeySpace.taggedKey(
                realmId, relativePrefix(offline) + "corresponding-session-index:" + correspondingId);
    }

    public static String clientIndex(String realmId, String clientId, boolean offline) {
        String base = offline ? "authenticated-client-offline:" : "authenticated-client:";
        return RedisKeySpace.taggedKey(realmId, base + "client-index:" + clientId);
    }

    /** String counter of active client sessions for {@code getActiveClientSessionStats}. */
    public static String clientStats(String realmId, String clientId, boolean offline) {
        return RedisKeySpace.taggedKey(
                realmId, relativePrefix(offline) + "client-stats:" + clientId);
    }

    /** SET of clientIds that currently have a non-zero stats counter in the realm. */
    public static String clientStatsIndex(String realmId, boolean offline) {
        return RedisKeySpace.taggedKey(realmId, relativePrefix(offline) + "client-stats-index");
    }

    /** ZSET of session ids scored by lastSessionRefresh (seconds). */
    public static String realmZIndex(String realmId, boolean offline) {
        return RedisKeySpace.taggedKey(realmId, relativePrefix(offline) + "realm-z");
    }

    /** ZSET of session ids for a client, scored by lastSessionRefresh. */
    public static String clientZIndex(String realmId, String clientId, boolean offline) {
        return RedisKeySpace.taggedKey(
                realmId, relativePrefix(offline) + "client-z:" + clientId);
    }

    private static String relativePrefix(boolean offline) {
        return offline ? "user-session-offline:" : "user-session:";
    }
}
