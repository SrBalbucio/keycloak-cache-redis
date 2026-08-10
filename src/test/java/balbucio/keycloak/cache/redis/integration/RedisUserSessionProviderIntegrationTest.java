package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.userSession.AuthenticatedClientSessionKey;
import balbucio.keycloak.cache.redis.userSession.RedisUserSessionProvider;
import balbucio.keycloak.cache.redis.userSession.UserSessionIndexes;
import balbucio.keycloak.cache.redis.userSession.UserSessionKey;
import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionModel.SessionPersistenceState;

class RedisUserSessionProviderIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void createAndReadUserSessionRoundTrip() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);

        UserSessionModel created =
                provider.createUserSession(
                        "s1", realm, user, "alice", "10.0.0.1", "form", true, null, null,
                        SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(1L, conn.sync().exists(UserSessionKey.of("s1", false).key()));
        assertTrue(conn.sync().smembers(UserSessionIndexes.userIndex(TestSessions.USER_ID, false)).contains("s1"));
        assertTrue(conn.sync().smembers(UserSessionIndexes.realmIndex(TestSessions.REALM_ID, false)).contains("s1"));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100);

        UserSessionModel loaded = provider2.getUserSession(realm2, "s1");
        assertNotNull(loaded);
        assertEquals("alice", loaded.getLoginUsername());
        assertEquals("10.0.0.1", loaded.getIpAddress());
        assertEquals("form", loaded.getAuthMethod());
        assertTrue(loaded.isRememberMe());
        assertTrue(loaded.isOffline() == false);
        assertEquals(TestSessions.USER_ID, loaded.getUser().getId());
        assertTrue(loaded.getStarted() > 0);
        assertTrue(loaded.getLastSessionRefresh() > 0);
    }

    @Test
    void notesStateAndRefreshPersistAcrossReads() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);

        UserSessionModel us =
                provider.createUserSession(
                        "s1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        us.setNote("theme", "dark");
        us.setState(UserSessionModel.State.LOGGED_IN);
        int refresh = org.keycloak.common.util.Time.currentTime();
        us.setLastSessionRefresh(refresh);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100);

        UserSessionModel loaded = provider2.getUserSession(realm2, "s1");
        assertEquals("dark", loaded.getNote("theme"));
        assertEquals(UserSessionModel.State.LOGGED_IN, loaded.getState());
        assertEquals(refresh, loaded.getLastSessionRefresh());
    }

    @Test
    void clientSessionLifecycleAndCleanupOnUserSessionRemoval() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        UserSessionModel us =
                provider.createUserSession(
                        "s1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        AuthenticatedClientSessionModel cs = provider.createClientSession(realm, client, us);
        session.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        String compound = "s1::client-1";
        assertTrue(conn.sync().smembers(UserSessionIndexes.clientIndex("client-1", false)).contains(compound));
        assertEquals(1, us.getAuthenticatedClientSessions().size());
        assertNotNull(provider.getClientSession(us, client, false));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100);
        UserSessionModel us2 = provider2.getUserSession(realm2, "s1");
        provider2.removeUserSession(realm2, us2);
        session2.getTransactionManager().commit();

        assertNull(provider2.getUserSession(realm2, "s1"));
        assertEquals(0L, conn.sync().exists(UserSessionKey.of("s1", false).key()));
        assertEquals(0L, conn.sync().exists(AuthenticatedClientSessionKey.of("s1", "client-1", false).key()));
        assertTrue(conn.sync().smembers(UserSessionIndexes.realmIndex(TestSessions.REALM_ID, false)).isEmpty());
        assertTrue(conn.sync().smembers(UserSessionIndexes.clientIndex("client-1", false)).isEmpty());
    }

    @Test
    void getUserSessionsStreamByUserAndRemoveAll() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);

        provider.createUserSession("s1", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        provider.createUserSession("s2", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        UserModel user2 = session2.users().getUserById(realm2, TestSessions.USER_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100);

        assertEquals(2, provider2.getUserSessionsStream(realm2, user2).count());

        provider2.removeUserSessions(realm2, user2);
        session2.getTransactionManager().commit();

        assertEquals(0, provider2.getUserSessionsStream(realm2, user2).count());
        assertEquals(0L, provider().sync().exists(UserSessionKey.of("s1", false).key()));
        assertEquals(0L, provider().sync().exists(UserSessionKey.of("s2", false).key()));
    }

    @Test
    void offlineSessionPersistsAndRemoves() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);

        UserSessionModel online =
                provider.createUserSession(
                        "s1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100);

        UserSessionModel online2 = provider2.getUserSession(realm2, "s1");
        provider2.createOfflineUserSession(online2);
        session2.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        String offlineKey = UserSessionKey.of("s1", true).key();
        assertEquals(1L, conn.sync().exists(offlineKey));
        assertTrue(conn.sync().smembers(UserSessionIndexes.realmIndex(TestSessions.REALM_ID, true)).contains("s1"));

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider3 = new RedisUserSessionProvider(session3, 100);

        UserSessionModel loaded = provider3.getOfflineUserSession(realm3, "s1");
        assertNotNull(loaded);
        assertTrue(loaded.isOffline());
        assertEquals(online.getId(), loaded.getNote(UserSessionModel.CORRESPONDING_SESSION_ID));

        provider3.removeOfflineUserSession(realm3, loaded);
        session3.getTransactionManager().commit();

        assertNull(provider3.getOfflineUserSession(realm3, "s1"));
        assertEquals(0L, conn.sync().exists(offlineKey));
    }

    @Test
    void brokerSessionLookupUsesIndex() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);

        provider.createUserSession("s1", realm, user, "alice", "ip", "form", false,
                "broker-sess-1", "broker-user-1", SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        assertEquals("s1", provider.getUserSessionByBrokerSessionId(realm, "broker-sess-1").getId());
        assertEquals(1, provider.getUserSessionByBrokerUserIdStream(realm, "broker-user-1").count());
    }

    @Test
    void expiredSessionIsNotReturned() throws Exception {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100);

        provider.createUserSession("s1", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        provider().sync().pexpire(UserSessionKey.of("s1", false).key(), 50);
        Thread.sleep(150);

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100);

        assertNull(provider2.getUserSession(realm2, "s1"));
        assertEquals(0L, provider().sync().exists(UserSessionKey.of("s1", false).key()));
    }
}
