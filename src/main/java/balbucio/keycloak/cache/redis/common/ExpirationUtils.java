package balbucio.keycloak.cache.redis.common;

import org.keycloak.common.util.Time;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;

/**
 * Helpers to compute session absolute expiration (epoch millis) for Redis TTL.
 */
public final class ExpirationUtils {

    private ExpirationUtils() {}

    public static long userSessionExpireAtMillis(RealmModel realm, UserSessionModel session) {
        long now = Time.currentTimeMillis();
        int maxLifespan;
        int idle;
        if (session.isOffline()) {
            maxLifespan = realm.isOfflineSessionMaxLifespanEnabled()
                    ? realm.getOfflineSessionMaxLifespan()
                    : 0;
            idle = realm.getOfflineSessionIdleTimeout();
        } else if (session.isRememberMe() && realm.getSsoSessionMaxLifespanRememberMe() > 0) {
            maxLifespan = realm.getSsoSessionMaxLifespanRememberMe();
            idle = realm.getSsoSessionIdleTimeoutRememberMe() > 0
                    ? realm.getSsoSessionIdleTimeoutRememberMe()
                    : realm.getSsoSessionIdleTimeout();
        } else {
            maxLifespan = realm.getSsoSessionMaxLifespan();
            idle = realm.getSsoSessionIdleTimeout();
        }

        long startedMillis = session.getStarted() * 1000L;
        long lastRefreshMillis = session.getLastSessionRefresh() * 1000L;
        long maxExpire = maxLifespan > 0 ? startedMillis + maxLifespan * 1000L : Long.MAX_VALUE;
        long idleExpire = idle > 0 ? lastRefreshMillis + idle * 1000L : Long.MAX_VALUE;
        long expireAt = Math.min(maxExpire, idleExpire);
        return expireAt == Long.MAX_VALUE ? now + 24L * 3600_000L : expireAt;
    }

    public static long clientSessionExpireAtMillis(
            RealmModel realm, UserSessionModel userSession, int timestampSeconds) {
        long userExpire = userSessionExpireAtMillis(realm, userSession);
        int clientIdle = userSession.isOffline()
                ? (realm.getClientOfflineSessionIdleTimeout() > 0
                        ? realm.getClientOfflineSessionIdleTimeout()
                        : realm.getOfflineSessionIdleTimeout())
                : (realm.getClientSessionIdleTimeout() > 0
                        ? realm.getClientSessionIdleTimeout()
                        : realm.getSsoSessionIdleTimeout());
        long idleExpire = clientIdle > 0 ? timestampSeconds * 1000L + clientIdle * 1000L : userExpire;
        return Math.min(userExpire, idleExpire);
    }
}
