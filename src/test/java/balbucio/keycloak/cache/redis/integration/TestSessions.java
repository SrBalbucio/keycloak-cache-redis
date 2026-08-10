package balbucio.keycloak.cache.redis.integration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

/**
 * Builds a lightweight mocked {@link KeycloakSession} wired to a real {@link RedisConnectionProvider}
 * plus in-memory realm/user stubs, so the Redis-backed session providers can be exercised against a
 * real Redis without booting a full Keycloak.
 */
public final class TestSessions {

    public static final String REALM_ID = "realm-a";
    public static final String USER_ID = "user-1";

    private TestSessions() {}

    public static KeycloakSession newSession(RedisConnectionProvider provider) {
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(RedisConnectionProvider.class)).thenReturn(provider);
        when(session.getTransactionManager()).thenReturn(new TestTransactionManager());
        when(session.getContext()).thenReturn(mock(KeycloakContext.class));

        RealmModel realm = newRealm(REALM_ID);
        RealmProvider realms = mock(RealmProvider.class);
        when(realms.getRealm(REALM_ID)).thenReturn(realm);
        when(session.realms()).thenReturn(realms);

        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn(USER_ID);
        UserProvider users = mock(UserProvider.class);
        when(users.getUserById(realm, USER_ID)).thenReturn(user);
        when(session.users()).thenReturn(users);

        return session;
    }

    public static RealmModel newRealm(String realmId) {
        RealmModel realm = mock(RealmModel.class);
        when(realm.getId()).thenReturn(realmId);
        when(realm.isOfflineSessionMaxLifespanEnabled()).thenReturn(false);
        when(realm.getOfflineSessionMaxLifespan()).thenReturn(36000);
        when(realm.getOfflineSessionIdleTimeout()).thenReturn(1800);
        when(realm.getSsoSessionMaxLifespan()).thenReturn(3600);
        when(realm.getSsoSessionIdleTimeout()).thenReturn(1800);
        when(realm.getSsoSessionMaxLifespanRememberMe()).thenReturn(0);
        when(realm.getSsoSessionIdleTimeoutRememberMe()).thenReturn(0);
        when(realm.getAccessCodeLifespanLogin()).thenReturn(300);
        when(realm.getAccessCodeLifespanUserAction()).thenReturn(300);
        when(realm.getMaxFailureWaitSeconds()).thenReturn(900);
        when(realm.getWaitIncrementSeconds()).thenReturn(60);
        return realm;
    }

    public static ClientModel newClient(String clientId) {
        ClientModel client = mock(ClientModel.class);
        when(client.getId()).thenReturn(clientId);
        when(client.getClientId()).thenReturn(clientId);
        return client;
    }

    public static void registerClient(RealmModel realm, ClientModel client) {
        when(realm.getClientById(client.getId())).thenReturn(client);
    }
}
