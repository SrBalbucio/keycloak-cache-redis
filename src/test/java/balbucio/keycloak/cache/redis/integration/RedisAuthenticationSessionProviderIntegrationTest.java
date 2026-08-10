package balbucio.keycloak.cache.redis.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.authSession.AuthSessionIndexes;
import balbucio.keycloak.cache.redis.authSession.RedisAuthenticationSessionProvider;
import balbucio.keycloak.cache.redis.authSession.RootAuthenticationSessionKey;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
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
}
