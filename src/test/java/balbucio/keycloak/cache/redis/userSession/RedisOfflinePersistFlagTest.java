package balbucio.keycloak.cache.redis.userSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.connection.RedisSync;
import balbucio.keycloak.cache.redis.integration.TestSessions;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.session.UserSessionPersisterProvider;

class RedisOfflinePersistFlagTest {

    @Test
    void persistOfflineSessionsFalseDoesNotTouchPersisterOnCreate() {
        RedisSync sync = mock(RedisSync.class);
        RedisConnectionProvider redis = mock(RedisConnectionProvider.class);
        when(redis.sync()).thenReturn(sync);

        KeycloakSession session = TestSessions.newSession(redis);
        UserSessionPersisterProvider persister = mock(UserSessionPersisterProvider.class);
        when(session.getProvider(UserSessionPersisterProvider.class)).thenReturn(persister);

        RedisUserSessionProvider provider = new RedisUserSessionProvider(session, 100, false);
        assertFalse(provider.isPersistOfflineSessions());

        var realm = session.realms().getRealm(TestSessions.REALM_ID);
        var user = session.users().getUserById(realm, TestSessions.USER_ID);
        var online =
                provider.createUserSession(
                        "s-offline-flag",
                        realm,
                        user,
                        "alice",
                        "ip",
                        "form",
                        false,
                        null,
                        null,
                        org.keycloak.models.UserSessionModel.SessionPersistenceState.PERSISTENT);
        provider.createOfflineUserSession(online);

        verify(persister, never())
                .createUserSession(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void factoryExposesPersistOfflineConfigDefaultFalse() {
        assertFalse(RedisUserSessionProviderFactory.DEFAULT_PERSIST_OFFLINE_SESSIONS);
        RedisUserSessionProviderFactory factory = new RedisUserSessionProviderFactory();
        assertEquals(
                1,
                factory.getConfigMetadata().stream()
                        .filter(
                                p ->
                                        RedisUserSessionProviderFactory.CONFIG_PERSIST_OFFLINE_SESSIONS
                                                .equals(p.getName()))
                        .count());
    }
}
