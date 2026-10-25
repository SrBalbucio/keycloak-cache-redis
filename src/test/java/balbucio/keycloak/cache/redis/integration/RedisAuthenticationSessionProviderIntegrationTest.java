package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.authSession.AuthSessionIndexes;
import balbucio.keycloak.cache.redis.authSession.RedisAuthenticationSessionProvider;
import balbucio.keycloak.cache.redis.authSession.RootAuthenticationSessionKey;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

class RedisAuthenticationSessionProviderIntegrationTest extends AbstractRedisIntegrationTest {

    @Test
    void createAndReadRootAuthenticationSession() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        session.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(1L, conn.sync().exists(RootAuthenticationSessionKey.of(TestSessions.REALM_ID, "root-1").key()));
        assertTrue(conn.sync().smembers(AuthSessionIndexes.realmIndex(TestSessions.REALM_ID)).contains("root-1"));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);

        RootAuthenticationSessionModel loaded = provider2.getRootAuthenticationSession(realm2, "root-1");
        assertNotNull(loaded);
        assertEquals("root-1", loaded.getId());
        assertEquals(TestSessions.REALM_ID, loaded.getRealm().getId());
        assertTrue(loaded.getTimestamp() > 0);
    }

    @Test
    void createTabAndReadBackWithClientMatch() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        AuthenticationSessionModel tab = root.createAuthenticationSession(client);
        tab.setAuthNote("step", "credentials");
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        TestSessions.registerClient(realm2, client);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);

        RootAuthenticationSessionModel loaded = provider2.getRootAuthenticationSession(realm2, "root-1");
        AuthenticationSessionModel back = loaded.getAuthenticationSession(client, tab.getTabId());
        assertNotNull(back);
        assertEquals(client.getId(), back.getClient().getId());
        assertEquals("credentials", back.getAuthNote("step"));
        assertEquals(tab.getTabId(), back.getTabId());
    }

    @Test
    void authSessionsLimitEvictsOldestTab() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 2);
        TestSessions.registerClient(realm, TestSessions.newClient("client-1"));
        TestSessions.registerClient(realm, TestSessions.newClient("client-2"));
        TestSessions.registerClient(realm, TestSessions.newClient("client-3"));

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        root.createAuthenticationSession(TestSessions.newClient("client-1"));
        root.createAuthenticationSession(TestSessions.newClient("client-2"));
        root.createAuthenticationSession(TestSessions.newClient("client-3"));
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 2);

        RootAuthenticationSessionModel loaded = provider2.getRootAuthenticationSession(realm2, "root-1");
        assertEquals(2, loaded.getAuthenticationSessions().size());
    }

    @Test
    void removeRootAuthenticationSessionCleansIndex() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);
        RootAuthenticationSessionModel root2 = provider2.getRootAuthenticationSession(realm2, "root-1");
        provider2.removeRootAuthenticationSession(realm2, root2);
        session2.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(0L, conn.sync().exists(RootAuthenticationSessionKey.of(TestSessions.REALM_ID, "root-1").key()));
        assertTrue(conn.sync().smembers(AuthSessionIndexes.realmIndex(TestSessions.REALM_ID)).isEmpty());

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider3 = new RedisAuthenticationSessionProvider(session3, 300);
        assertNull(provider3.getRootAuthenticationSession(realm3, "root-1"));
    }

    @Test
    void removingLastTabDeletesRoot() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel client = TestSessions.newClient("client-1");

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        AuthenticationSessionModel tab = root.createAuthenticationSession(client);
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);
        RootAuthenticationSessionModel root2 = provider2.getRootAuthenticationSession(realm2, "root-1");
        root2.removeAuthenticationSessionByTabId(tab.getTabId());
        session2.getTransactionManager().commit();

        assertEquals(0L, provider().sync().exists(RootAuthenticationSessionKey.of(TestSessions.REALM_ID, "root-1").key()));

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider3 = new RedisAuthenticationSessionProvider(session3, 300);
        assertNull(provider3.getRootAuthenticationSession(realm3, "root-1"));
    }

    @Test
    void onRealmRemovedCleansAllRoots() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);

        provider.createRootAuthenticationSession(realm, "root-1");
        provider.createRootAuthenticationSession(realm, "root-2");
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);
        provider2.onRealmRemoved(realm2);
        session2.getTransactionManager().commit();

        RedisConnectionProvider conn = provider();
        assertEquals(0L, conn.sync().exists(RootAuthenticationSessionKey.of(TestSessions.REALM_ID, "root-1").key()));
        assertEquals(0L, conn.sync().exists(RootAuthenticationSessionKey.of(TestSessions.REALM_ID, "root-2").key()));
        assertTrue(conn.sync().smembers(AuthSessionIndexes.realmIndex(TestSessions.REALM_ID)).isEmpty());
    }

    /**
     * Reproduces the login-success NPE where {@code TokenManager.attachAuthenticationSession} ->
     * {@code updateAuthenticationSessionAfterSuccessfulAuthentication} removes the tab and then
     * {@code AuthenticationManager.redirectAfterSuccessfulFlow} calls {@code authSession.getProtocol()},
     * which used to return {@code null} because the adapter was a live view over the root entity.
     * The adapter must keep returning its fields after the tab is removed (like the stock Infinispan
     * adapter, which holds a detached entity).
     */
    @Test
    void removedTabAdapterStillExposesFields() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        AuthenticationSessionModel tab = root.createAuthenticationSession(client);
        tab.setProtocol("openid-connect");
        tab.setRedirectUri("https://app.example/cb");
        tab.setAuthNote("step", "credentials");
        session.getTransactionManager().commit();

        // Simulate updateAuthenticationSessionAfterSuccessfulAuthentication: the tab is removed
        // while the same adapter reference is still held by the login flow.
        root.removeAuthenticationSessionByTabId(tab.getTabId());

        assertEquals("openid-connect", tab.getProtocol());
        assertEquals("https://app.example/cb", tab.getRedirectUri());
        assertEquals("credentials", tab.getAuthNote("step"));
        assertEquals(client.getId(), tab.getClient().getId());
    }

    /**
     * Same contract as above but reloaded in a fresh session, to ensure write-through still
     * persists protocol/notes for a tab that is removed later in the SAME request.
     */
    @Test
    void writesBeforeTabRemovalArePersisted() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel clientA = TestSessions.newClient("client-a");
        ClientModel clientB = TestSessions.newClient("client-b");
        TestSessions.registerClient(realm, clientA);
        TestSessions.registerClient(realm, clientB);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        AuthenticationSessionModel tabA = root.createAuthenticationSession(clientA);
        tabA.setProtocol("openid-connect");
        AuthenticationSessionModel tabB = root.createAuthenticationSession(clientB);
        tabB.setProtocol("openid-connect");
        // Remove tabA mid-request (as the success flow does); tabB must remain and be persisted.
        root.removeAuthenticationSessionByTabId(tabA.getTabId());
        session.getTransactionManager().commit();

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        TestSessions.registerClient(realm2, clientB);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);
        RootAuthenticationSessionModel loaded = provider2.getRootAuthenticationSession(realm2, "root-1");
        assertNotNull(loaded);
        assertEquals(1, loaded.getAuthenticationSessions().size());
        AuthenticationSessionModel back = loaded.getAuthenticationSession(clientB, tabB.getTabId());
        assertNotNull(back);
        assertEquals("openid-connect", back.getProtocol());
    }

    /**
     * KC 26.8 "sign out other devices": root sessions holding a tab authenticated as the user are
     * removed (except the one to keep), while roots without an authenticated tab and the kept
     * root survive. The by-user index must reflect the removals.
     */
    @Test
    void signOutOtherDevicesRemovesOtherRootSessionsOfUser() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        RootAuthenticationSessionModel keep = provider.createRootAuthenticationSession(realm, "keep-root");
        keep.createAuthenticationSession(client).setAuthenticatedUser(
                session.users().getUserById(realm, TestSessions.USER_ID));
        RootAuthenticationSessionModel other1 = provider.createRootAuthenticationSession(realm, "other-root-1");
        other1.createAuthenticationSession(client).setAuthenticatedUser(
                session.users().getUserById(realm, TestSessions.USER_ID));
        RootAuthenticationSessionModel other2 = provider.createRootAuthenticationSession(realm, "other-root-2");
        other2.createAuthenticationSession(client).setAuthenticatedUser(
                session.users().getUserById(realm, TestSessions.USER_ID));
        // No authenticated tab: must not be touched by the by-user sign-out.
        provider.createRootAuthenticationSession(realm, "anon-root");
        session.getTransactionManager().commit();

        assertEquals(
                3L,
                provider().sync().zcard(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID)));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);
        provider2.removeRootAuthenticationSessionsByAuthenticatedUser(
                realm2, session2.users().getUserById(realm2, TestSessions.USER_ID), "keep-root");
        session2.getTransactionManager().commit();

        KeycloakSession session3 = TestSessions.newSession(provider());
        RealmModel realm3 = session3.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider3 = new RedisAuthenticationSessionProvider(session3, 300);
        assertNotNull(provider3.getRootAuthenticationSession(realm3, "keep-root"));
        assertNull(provider3.getRootAuthenticationSession(realm3, "other-root-1"));
        assertNull(provider3.getRootAuthenticationSession(realm3, "other-root-2"));
        assertNotNull(provider3.getRootAuthenticationSession(realm3, "anon-root"));
        // Only the kept root remains in the index; the anonymous root was never a member.
        assertEquals(
                1L,
                provider().sync().zcard(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID)));
    }

    /**
     * Index members that no longer match reality (bogus id, or a member written for a user whose
     * tab later changed user) must be cleaned instead of deleting unrelated or valid sessions.
     */
    @Test
    void signOutSkipsAndCleansStaleIndexMembers() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        root.createAuthenticationSession(client).setAuthenticatedUser(
                session.users().getUserById(realm, TestSessions.USER_ID));
        session.getTransactionManager().commit();

        // Bogus member for user-1 and a stale member claiming root-1 belongs to user-2.
        long futureScore = System.currentTimeMillis() + 60_000L;
        provider().sync().zadd(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID), futureScore, "ghost-root");
        provider().sync().zadd(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, "user-2"), futureScore, "root-1");

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);

        UserModel otherUser = mock(UserModel.class);
        when(otherUser.getId()).thenReturn("user-2");
        // root-1's tabs belong to user-1: the stale user-2 member must not delete it.
        provider2.removeRootAuthenticationSessionsByAuthenticatedUser(realm2, otherUser, null);
        assertEquals(0L, provider().sync().zcard(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, "user-2")));
        assertNotNull(provider2.getRootAuthenticationSession(realm2, "root-1"));

        UserModel user1 = session2.users().getUserById(realm2, TestSessions.USER_ID);
        provider2.removeRootAuthenticationSessionsByAuthenticatedUser(realm2, user1, null);
        session2.getTransactionManager().commit();

        assertNull(provider2.getRootAuthenticationSession(realm2, "root-1"));
        assertEquals(0L, provider().sync().zcard(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID)));
    }

    /** Clearing the tab's authenticated user must drop the index member via the commit diff. */
    @Test
    void clearingTabUserUpdatesByUserIndex() {
        KeycloakSession session = TestSessions.newSession(provider());
        RealmModel realm = session.realms().getRealm(TestSessions.REALM_ID);
        RedisAuthenticationSessionProvider provider = new RedisAuthenticationSessionProvider(session, 300);
        ClientModel client = TestSessions.newClient("client-1");
        TestSessions.registerClient(realm, client);

        RootAuthenticationSessionModel root = provider.createRootAuthenticationSession(realm, "root-1");
        AuthenticationSessionModel tab = root.createAuthenticationSession(client);
        tab.setAuthenticatedUser(session.users().getUserById(realm, TestSessions.USER_ID));
        session.getTransactionManager().commit();
        assertEquals(
                1L,
                provider().sync().zcard(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID)));

        KeycloakSession session2 = TestSessions.newSession(provider());
        RealmModel realm2 = session2.realms().getRealm(TestSessions.REALM_ID);
        TestSessions.registerClient(realm2, client);
        RedisAuthenticationSessionProvider provider2 = new RedisAuthenticationSessionProvider(session2, 300);
        RootAuthenticationSessionModel loaded = provider2.getRootAuthenticationSession(realm2, "root-1");
        AuthenticationSessionModel reloaded = loaded.getAuthenticationSession(client, tab.getTabId());
        reloaded.setAuthenticatedUser(null);
        session2.getTransactionManager().commit();

        assertEquals(
                0L,
                provider().sync().zcard(AuthSessionIndexes.userIndex(TestSessions.REALM_ID, TestSessions.USER_ID)));
        assertNotNull(provider2.getRootAuthenticationSession(realm2, "root-1"));
    }
}
