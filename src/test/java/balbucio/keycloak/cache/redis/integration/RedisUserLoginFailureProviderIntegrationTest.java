package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.loginFailure.LoginFailureIndexes;
import balbucio.keycloak.cache.redis.loginFailure.LoginFailureKey;
import balbucio.keycloak.cache.redis.loginFailure.RedisUserLoginFailureProvider;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;

class RedisUserLoginFailureProviderIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void incrementAndReadLoginFailures() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider = new RedisUserLoginFailureProvider(session);

        UserLoginFailureModel failure = provider.addUserLoginFailure(realm, TestSessions.USER_ID);
        failure.incrementFailures();
        failure.setLastIPFailure("1.2.3.4");
        session.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(1L, conn.sync().exists(LoginFailureKey.of(TestSessions.REALM_ID, TestSessions.USER_ID).key()));
        assertTrue(conn.sync().smembers(LoginFailureIndexes.realmIndex(TestSessions.REALM_ID)).contains(TestSessions.USER_ID));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider2 = new RedisUserLoginFailureProvider(session2);

        UserLoginFailureModel loaded = provider2.getUserLoginFailure(realm2, TestSessions.USER_ID);
        assertNotNull(loaded);
        assertEquals(1, loaded.getNumFailures());
        assertEquals("1.2.3.4", loaded.getLastIPFailure());
        assertTrue(loaded.getLastFailure() > 0);
    }

    @Test
    void clearFailuresResetsCounters() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider = new RedisUserLoginFailureProvider(session);

        UserLoginFailureModel failure = provider.addUserLoginFailure(realm, TestSessions.USER_ID);
        failure.incrementFailures();
        failure.incrementFailures();
        failure.setFailedLoginNotBefore(999);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider2 = new RedisUserLoginFailureProvider(session2);
        UserLoginFailureModel loaded = provider2.getUserLoginFailure(realm2, TestSessions.USER_ID);
        loaded.clearFailures();
        session2.getTransactionManager().commit();

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider3 = new RedisUserLoginFailureProvider(session3);
        UserLoginFailureModel after = provider3.getUserLoginFailure(realm3, TestSessions.USER_ID);
        assertEquals(0, after.getNumFailures());
        assertEquals(0, after.getFailedLoginNotBefore());
        assertNull(after.getLastIPFailure());
    }

    @Test
    void removeSingleAndRemoveAll() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider = new RedisUserLoginFailureProvider(session);

        provider.addUserLoginFailure(realm, "user-1").incrementFailures();
        provider.addUserLoginFailure(realm, "user-2").incrementFailures();
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider2 = new RedisUserLoginFailureProvider(session2);
        provider2.removeUserLoginFailure(realm2, "user-1");
        session2.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(0L, conn.sync().exists(LoginFailureKey.of(TestSessions.REALM_ID, "user-1").key()));
        assertEquals(1L, conn.sync().exists(LoginFailureKey.of(TestSessions.REALM_ID, "user-2").key()));

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisUserLoginFailureProvider provider3 = new RedisUserLoginFailureProvider(session3);
        provider3.removeAllUserLoginFailures(realm3);
        session3.getTransactionManager().commit();

        assertEquals(0L, conn.sync().exists(LoginFailureKey.of(TestSessions.REALM_ID, "user-2").key()));
        assertTrue(conn.sync().smembers(LoginFailureIndexes.realmIndex(TestSessions.REALM_ID)).isEmpty());
    }
}
