package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.authSession.RedisAuthenticationSessionProvider;
import balbucio.keycloak.cache.redis.authSession.RootAuthenticationSessionKey;
import balbucio.keycloak.cache.redis.loginFailure.LoginFailureKey;
import balbucio.keycloak.cache.redis.loginFailure.RedisUserLoginFailureProvider;
import balbucio.keycloak.cache.redis.singleUseObject.RedisSingleUseObjectProvider;
import balbucio.keycloak.cache.redis.singleUseObject.SingleUseObjectKey;
import balbucio.keycloak.cache.redis.userSession.RedisUserSessionProvider;
import balbucio.keycloak.cache.redis.userSession.UserSessionKey;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionModel.SessionPersistenceState;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * TTL matrix: asserts the Redis {@code PTTL} written for every entity type matches the realm
 * policy computed by {@code ExpirationUtils} / the provider-specific expiration rules.
 *
 * <p>Realm defaults from {@link TestSessions#newRealm}: sso max-lifespan 3600s, sso idle 1800s,
 * offline idle 1800s (max-lifespan disabled), access-code lifespan 300s, max failure wait 900s,
 * wait increment 60s.
 */
class RedisTtlMatrixIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void onlineSessionTtlUsesIdleTimeoutWhenSmallerThanMaxLifespan() {
        Node node = new Node();
        node.createUserSession("ttl-online", false);
        node.commit();

        // min(started + 3600s, lastRefresh + 1800s) = 1800s
        assertTtl(UserSessionKey.of(TestSessions.REALM_ID, "ttl-online", false).key(), 1_800_000L, 30_000L);
    }

    @Test
    void rememberMeSessionUsesRememberMeTimeouts() {
        Node node = new Node();
        when(node.realm().getSsoSessionMaxLifespanRememberMe()).thenReturn(7200);
        when(node.realm().getSsoSessionIdleTimeoutRememberMe()).thenReturn(3600);
        node.createUserSession("ttl-remember", true);
        node.commit();

        // min(started + 7200s, lastRefresh + 3600s) = 3600s
        assertTtl(UserSessionKey.of(TestSessions.REALM_ID, "ttl-remember", false).key(), 3_600_000L, 30_000L);
    }

    @Test
    void offlineSessionTtlUsesOfflineIdleTimeout() {
        Node node = new Node();
        UserSessionModel online = node.createUserSession("ttl-offline", false);
        node.provider().createOfflineUserSession(online);
        node.commit();

        // offline max-lifespan disabled -> idle 1800s dominates
        assertTtl(UserSessionKey.of(TestSessions.REALM_ID, "ttl-offline", true).key(), 1_800_000L, 30_000L);
    }

    @Test
    void offlineSessionTtlHonoursMaxLifespanWhenEnabledAndSmaller() {
        Node node = new Node();
        when(node.realm().isOfflineSessionMaxLifespanEnabled()).thenReturn(true);
        when(node.realm().getOfflineSessionMaxLifespan()).thenReturn(600);
        UserSessionModel online = node.createUserSession("ttl-offline-max", false);
        node.provider().createOfflineUserSession(online);
        node.commit();

        // min(started + 600s, lastRefresh + 1800s) = 600s
        assertTtl(UserSessionKey.of(TestSessions.REALM_ID, "ttl-offline-max", true).key(), 600_000L, 30_000L);
    }

    @Test
    void sessionTtlIsRefreshedWhenLastSessionRefreshAdvances() {
        Node node = new Node();
        node.createUserSession("ttl-refresh", false);
        node.commit();

        Node updater = new Node();
        UserSessionModel loaded = updater.provider().getUserSession(updater.realm(), "ttl-refresh");
        assertNotNull(loaded);
        loaded.setLastSessionRefresh(Time.currentTime() + 100);
        updater.commit();

        // idle expiration moved 100s into the future: ~1900s remaining
        assertTtl(UserSessionKey.of(TestSessions.REALM_ID, "ttl-refresh", false).key(), 1_900_000L, 30_000L);
    }

    @Test
    void authSessionTtlUsesAccessCodeLifespanLogin() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 10);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "ttl-auth");
        assertNotNull(root);
        session.getTransactionManager().commit();

        // accessCodeLifespanLogin = 300s
        assertTtl(RootAuthenticationSessionKey.of(TestSessions.REALM_ID, "ttl-auth").key(), 300_000L, 30_000L);
    }

    @Test
    void loginFailureTtlCoversDoubleLockoutWindow() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider = new RedisUserLoginFailureProvider(session);

        UserLoginFailureModel failure = provider.addUserLoginFailure(realm, TestSessions.USER_ID);
        failure.incrementFailures();
        session.getTransactionManager().commit();

        // lifespan = max(maxFailureWait 900s, waitIncrement 60s); kept for 2x lifespan = 1800s
        assertTtl(LoginFailureKey.of(TestSessions.REALM_ID, TestSessions.USER_ID).key(), 1_800_000L, 30_000L);
    }

    @Test
    void singleUseObjectTtlMatchesRequestedLifespan() {
        KeycloakSession session = TestSessions.newSession(provider());
        RedisSingleUseObjectProvider provider = new RedisSingleUseObjectProvider(session);

        provider.put("ttl-single", 60, Map.of("k", "v"));

        assertTtl(SingleUseObjectKey.of("ttl-single").key(), 60_000L, 15_000L);
    }

    private void assertTtl(String key, long expectedMillis, long toleranceMillis) {
        Long pttl = provider().sync().pttl(key);
        assertNotNull(pttl, "key should exist and carry a TTL: " + key);
        assertTrue(pttl > 0, "key should carry a positive TTL, got " + pttl + " for " + key);
        assertTrue(
                pttl <= expectedMillis && pttl > expectedMillis - toleranceMillis,
                "TTL for " + key + " expected ~" + expectedMillis + "ms (tolerance " + toleranceMillis
                        + "ms) but was " + pttl + "ms");
    }

    private static final class Node {
        private final KeycloakSession session = TestSessions.newSession(IntegrationRedis.provider());
        private final RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

        RedisUserSessionProvider provider() {
            return provider;
        }

        RealmModel realm() {
            return session.realms().getRealm(TestSessions.REALM_ID);
        }

        UserModel user() {
            return session.users().getUserById(realm(), TestSessions.USER_ID);
        }

        UserSessionModel createUserSession(String id, boolean rememberMe) {
            return provider.createUserSession(
                    id, realm(), user(), "alice", "ip", "form", rememberMe, null, null,
                    SessionPersistenceState.PERSISTENT);
        }

        void commit() {
            session.getTransactionManager().commit();
        }
    }
}
