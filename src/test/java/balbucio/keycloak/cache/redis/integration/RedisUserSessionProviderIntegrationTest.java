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
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

        UserSessionModel created =
                provider.createUserSession(
                        "s1", realm, user, "alice", "10.0.0.1", "form", true, null, null,
                        SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(1L, conn.sync().exists(UserSessionKey.of(TestSessions.REALM_ID, "s1", false).key()));
        assertTrue(conn.sync().smembers(UserSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID, false)).contains("s1"));
        assertTrue(conn.sync().smembers(UserSessionIndexes.realmIndex(TestSessions.REALM_ID, false)).contains("s1"));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);

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
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

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
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);

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
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        UserSessionModel us =
                provider.createUserSession(
                        "s1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        AuthenticatedClientSessionModel cs = provider.createClientSession(realm, client, us);
        session.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertTrue(conn.sync().smembers(UserSessionIndexes.clientIndex(TestSessions.REALM_ID, "client-1", false)).contains("s1"));
        assertTrue(conn.sync().zrevrange(UserSessionIndexes.clientZIndex(TestSessions.REALM_ID, "client-1", false), 0, -1).contains("s1"));
        assertTrue(UserSessionKey.of(TestSessions.REALM_ID, "s1", false).key().contains("{" + TestSessions.REALM_ID + "}"));
        assertEquals(1, us.getAuthenticatedClientSessions().size());
        assertNotNull(provider.getClientSession(us, client, false));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);
        UserSessionModel us2 = provider2.getUserSession(realm2, "s1");
        provider2.removeUserSession(realm2, us2);
        session2.getTransactionManager().commit();

        assertNull(provider2.getUserSession(realm2, "s1"));
        assertEquals(0L, conn.sync().exists(UserSessionKey.of(TestSessions.REALM_ID, "s1", false).key()));
        assertEquals(0L, conn.sync().exists(AuthenticatedClientSessionKey.of(TestSessions.REALM_ID, "s1", "client-1", false).key()));
        assertTrue(conn.sync().smembers(UserSessionIndexes.realmIndex(TestSessions.REALM_ID, false)).isEmpty());
        assertTrue(conn.sync().smembers(UserSessionIndexes.clientIndex(TestSessions.REALM_ID, "client-1", false)).isEmpty());
    }

    @Test
    void getUserSessionsStreamByUserAndRemoveAll() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

        provider.createUserSession("s1", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        provider.createUserSession("s2", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        UserModel user2 = session2.users().getUserById(realm2, TestSessions.USER_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);

        assertEquals(2, provider2.getUserSessionsStream(realm2, user2).count());

        provider2.removeUserSessions(realm2, user2);
        session2.getTransactionManager().commit();

        assertEquals(0, provider2.getUserSessionsStream(realm2, user2).count());
        assertEquals(0L, provider().sync().exists(UserSessionKey.of(TestSessions.REALM_ID, "s1", false).key()));
        assertEquals(0L, provider().sync().exists(UserSessionKey.of(TestSessions.REALM_ID, "s2", false).key()));
    }

    @Test
    void offlineSessionPersistsAndRemoves() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

        UserSessionModel online =
                provider.createUserSession(
                        "s1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);

        UserSessionModel online2 = provider2.getUserSession(realm2, "s1");
        provider2.createOfflineUserSession(online2);
        session2.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        String offlineKey = UserSessionKey.of(TestSessions.REALM_ID, "s1", true).key();
        assertEquals(1L, conn.sync().exists(offlineKey));
        assertTrue(conn.sync().smembers(UserSessionIndexes.realmIndex(TestSessions.REALM_ID, true)).contains("s1"));

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider3 = new RedisUserSessionProvider(session3, 100, false);

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
    void clientSessionStatsAndPaginationUseCountersAndZset() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);
        ClientModel client = TestSessions.newClient("client-stats");
        TestSessions.registerClient(realm, client);

        UserSessionModel s1 =
                provider.createUserSession(
                        "ps1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        UserSessionModel s2 =
                provider.createUserSession(
                        "ps2", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        provider.createClientSession(realm, client, s1);
        provider.createClientSession(realm, client, s2);
        s1.setLastSessionRefresh(org.keycloak.common.util.Time.currentTime() + 10);
        s2.setLastSessionRefresh(org.keycloak.common.util.Time.currentTime() + 20);
        session.getTransactionManager().commit();

        assertEquals(2L, provider.getActiveClientSessionStats(realm, false).get("client-stats"));
        assertEquals(2L, provider.getActiveUserSessions(realm, client));

        var page =
                provider.getUserSessionsStream(realm, client, 0, 1).map(UserSessionModel::getId).toList();
        assertEquals(1, page.size());
        assertEquals("ps2", page.get(0));
    }

    /**
     * Regression: Keycloak 26.6+ admin console calls {@code readOnlyStreamUserSessions}, whose
     * default delegates to {@code getUserSessionsStream(realm, client, -1, -1)} where negative
     * values mean "no limit". The provider must treat negative maxResults as "no limit", not
     * "return empty".
     */
    @Test
    void negativeMaxResultsTreatedAsNoLimit() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);
        ClientModel client = TestSessions.newClient("client-neg");
        TestSessions.registerClient(realm, client);

        UserSessionModel s1 = provider.createUserSession(
                "ns1", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        UserSessionModel s2 = provider.createUserSession(
                "ns2", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        provider.createClientSession(realm, client, s1);
        provider.createClientSession(realm, client, s2);
        session.getTransactionManager().commit();

        // Simulates readOnlyStreamUserSessions(realm, client, -1, -1)
        var all = provider.getUserSessionsStream(realm, client, -1, -1)
                .map(UserSessionModel::getId).toList();
        assertEquals(2, all.size(), "negative maxResults should mean no limit, not empty");

        // maxResults == 0 still returns empty (Keycloak contract)
        assertTrue(provider.getUserSessionsStream(realm, client, 0, 0).findAny().isEmpty());

        // Explicit limit still works
        assertEquals(1, provider.getUserSessionsStream(realm, client, 0, 1).count());
    }

    @Test
    void brokerSessionLookupUsesIndex() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

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
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);

        provider.createUserSession("s1", realm, user, "alice", "ip", "form", false, null, null,
                SessionPersistenceState.PERSISTENT);
        session.getTransactionManager().commit();

        provider().sync().pexpire(UserSessionKey.of(TestSessions.REALM_ID, "s1", false).key(), 50);
        Thread.sleep(150);

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);

        assertNull(provider2.getUserSession(realm2, "s1"));
        assertEquals(0L, provider().sync().exists(UserSessionKey.of(TestSessions.REALM_ID, "s1", false).key()));
    }

    /**
     * Characterizes {@code detachFromUserSession}: the client session is removed from storage and
     * unlinked from the user session. Note: the held adapter is marked-for-delete by the provider
     * during detach, so reads on that same reference afterward throw {@code ModelIllegalStateException}
     * (the SPI is fail-fast here, whereas the stock Infinispan adapter keeps returning its detached
     * entity). Whether any core logout flow reads a detached client session is tracked as a follow-up
     * in {@code docs/spec-authsession-and-realm-cache-fix.md} (auditoria de adapters).
     */
    @Test
    void detachFromUserSessionRemovesClientSessionAndUnlinks() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        UserModel user = session.users().getUserById(realm, TestSessions.USER_ID);
        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);
        ClientModel client = TestSessions.newClient("client-detach");
        TestSessions.registerClient(realm, client);

        UserSessionModel us =
                provider.createUserSession(
                        "sd1", realm, user, "alice", "ip", "form", false, null, null,
                        SessionPersistenceState.PERSISTENT);
        AuthenticatedClientSessionModel cs = provider.createClientSession(realm, client, us);
        cs.setProtocol("openid-connect");
        assertEquals(1, us.getAuthenticatedClientSessions().size());

        cs.detachFromUserSession();
        session.getTransactionManager().commit();

        // Fresh session: the client session is unlinked from the user session and gone from storage.
        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        TestSessions.registerClient(realm2, client);
        RedisUserSessionProvider provider2 = new RedisUserSessionProvider(session2, 100, false);
        UserSessionModel loaded = provider2.getUserSession(realm2, "sd1");
        assertNotNull(loaded);
        assertEquals(0, loaded.getAuthenticatedClientSessions().size());
        assertEquals(
                0L,
                provider().sync().exists(AuthenticatedClientSessionKey.of(
                        TestSessions.REALM_ID, "sd1", "client-detach", false).key()));
    }
}
