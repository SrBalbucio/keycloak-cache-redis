package balbucio.keycloak.cache.redis.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;

class ExpirationUtilsTest {

    private RealmModel realm;
    private UserSessionModel session;

    @BeforeEach
    void setUp() {
        realm = mock(RealmModel.class);
        session = mock(UserSessionModel.class);

        when(session.isOffline()).thenReturn(false);
        when(session.isRememberMe()).thenReturn(false);

        long now = Time.currentTimeMillis();
        when(session.getStarted()).thenReturn((int) (now / 1000) - 600);
        when(session.getLastSessionRefresh()).thenReturn((int) (now / 1000) - 120);

        when(realm.isOfflineSessionMaxLifespanEnabled()).thenReturn(false);
        when(realm.getSsoSessionMaxLifespan()).thenReturn(3600);
        when(realm.getSsoSessionIdleTimeout()).thenReturn(1800);
    }

    @Test
    void onlineSessionExpiresAtMinOfMaxLifespanAndIdle() {
        long startedMs = session.getStarted() * 1000L;
        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long expected = Math.min(startedMs + 3600_000L, lastRefreshMs + 1800_000L);

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void onlineSessionMaxLifespanWinsWhenIdleIsLarge() {
        when(realm.getSsoSessionIdleTimeout()).thenReturn(7200);
        when(realm.getSsoSessionMaxLifespan()).thenReturn(1800);

        long startedMs = session.getStarted() * 1000L;
        long expected = startedMs + 1800_000L;

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void offlineSessionUsesOfflineTimeoutsWhenMaxLifespanEnabled() {
        when(session.isOffline()).thenReturn(true);
        when(realm.isOfflineSessionMaxLifespanEnabled()).thenReturn(true);
        when(realm.getOfflineSessionMaxLifespan()).thenReturn(86400);
        when(realm.getOfflineSessionIdleTimeout()).thenReturn(3600);

        long startedMs = session.getStarted() * 1000L;
        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long expected = Math.min(startedMs + 86400_000L, lastRefreshMs + 3600_000L);

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void offlineSessionIgnoresMaxLifespanWhenDisabled() {
        when(session.isOffline()).thenReturn(true);
        when(realm.getOfflineSessionIdleTimeout()).thenReturn(3600);

        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long expected = lastRefreshMs + 3600_000L;

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void rememberMeSessionUsesRememberMeTimeouts() {
        when(session.isRememberMe()).thenReturn(true);
        when(realm.getSsoSessionMaxLifespanRememberMe()).thenReturn(7200);
        when(realm.getSsoSessionIdleTimeoutRememberMe()).thenReturn(1200);

        long startedMs = session.getStarted() * 1000L;
        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long expected = Math.min(startedMs + 7200_000L, lastRefreshMs + 1200_000L);

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void rememberMeSessionFallsBackIdleToSsoIdle() {
        when(session.isRememberMe()).thenReturn(true);
        when(realm.getSsoSessionMaxLifespanRememberMe()).thenReturn(7200);
        when(realm.getSsoSessionIdleTimeoutRememberMe()).thenReturn(0);

        long startedMs = session.getStarted() * 1000L;
        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long expected = Math.min(startedMs + 7200_000L, lastRefreshMs + 1800_000L);

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void rememberMeFallsBackToSsoWhenRememberMeMaxIsZero() {
        when(session.isRememberMe()).thenReturn(true);
        when(realm.getSsoSessionMaxLifespanRememberMe()).thenReturn(0);

        long startedMs = session.getStarted() * 1000L;
        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long expected = Math.min(startedMs + 3600_000L, lastRefreshMs + 1800_000L);

        assertEquals(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void allTimeoutsZeroFallsBackToTwentyFourHours() {
        when(realm.getSsoSessionMaxLifespan()).thenReturn(0);
        when(realm.getSsoSessionIdleTimeout()).thenReturn(0);

        long now = Time.currentTimeMillis();
        long expected = now + 24L * 3600_000L;

        assertApprox(expected, ExpirationUtils.userSessionExpireAtMillis(realm, session));
    }

    @Test
    void clientSessionOnlineUsesClientIdleWhenSmaller() {
        when(realm.getClientSessionIdleTimeout()).thenReturn(300);

        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long userExpire = ExpirationUtils.userSessionExpireAtMillis(realm, session);
        long expected = Math.min(userExpire, lastRefreshMs + 300_000L);

        assertEquals(
                expected,
                ExpirationUtils.clientSessionExpireAtMillis(realm, session, (int) (lastRefreshMs / 1000)));
    }

    @Test
    void clientSessionOnlineFallsBackToSsoIdleWhenClientIdleZero() {
        when(realm.getClientSessionIdleTimeout()).thenReturn(0);

        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long userExpire = ExpirationUtils.userSessionExpireAtMillis(realm, session);
        long expected = Math.min(userExpire, lastRefreshMs + 1800_000L);

        assertEquals(
                expected,
                ExpirationUtils.clientSessionExpireAtMillis(realm, session, (int) (lastRefreshMs / 1000)));
    }

    @Test
    void clientSessionOfflineUsesClientOfflineIdle() {
        when(session.isOffline()).thenReturn(true);
        when(realm.getOfflineSessionIdleTimeout()).thenReturn(3600);
        when(realm.getClientOfflineSessionIdleTimeout()).thenReturn(600);

        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long userExpire = ExpirationUtils.userSessionExpireAtMillis(realm, session);
        long expected = Math.min(userExpire, lastRefreshMs + 600_000L);

        assertEquals(
                expected,
                ExpirationUtils.clientSessionExpireAtMillis(realm, session, (int) (lastRefreshMs / 1000)));
    }

    @Test
    void clientSessionOfflineFallsBackToOfflineIdleWhenClientOfflineIdleZero() {
        when(session.isOffline()).thenReturn(true);
        when(realm.getOfflineSessionIdleTimeout()).thenReturn(3600);
        when(realm.getClientOfflineSessionIdleTimeout()).thenReturn(0);

        long lastRefreshMs = session.getLastSessionRefresh() * 1000L;
        long userExpire = ExpirationUtils.userSessionExpireAtMillis(realm, session);
        long expected = Math.min(userExpire, lastRefreshMs + 3600_000L);

        assertEquals(
                expected,
                ExpirationUtils.clientSessionExpireAtMillis(realm, session, (int) (lastRefreshMs / 1000)));
    }

    @Test
    void clientSessionUserExpireWinsWhenClientIdleIsLarge() {
        when(realm.getClientSessionIdleTimeout()).thenReturn(7200);

        long userExpire = ExpirationUtils.userSessionExpireAtMillis(realm, session);

        assertEquals(
                userExpire,
                ExpirationUtils.clientSessionExpireAtMillis(
                        realm, session, (int) (session.getLastSessionRefresh() * 1000L / 1000)));
    }

    private static void assertApprox(long expected, long actual) {
        assertTrue(
                Math.abs(expected - actual) <= 2_000L,
                "expected ~" + expected + " but got " + actual);
    }
}
