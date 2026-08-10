package balbucio.keycloak.cache.redis.singleUseObject;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.integration.AbstractRedisIntegrationTest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.session.RevokedTokenPersisterProvider;

class RedisSingleUseObjectPersistTest extends AbstractRedisIntegrationTest {

    @Test
    void persistRevokedTokensFalseDoesNotCallPersister() {
        RedisConnectionProvider redis = provider();
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(RedisConnectionProvider.class)).thenReturn(redis);
        RevokedTokenPersisterProvider persister = mock(RevokedTokenPersisterProvider.class);
        when(session.getProvider(RevokedTokenPersisterProvider.class)).thenReturn(persister);

        RedisSingleUseObjectProvider suo = new RedisSingleUseObjectProvider(session, false);
        suo.put("token-a" + SingleUseObjectProvider.REVOKED_KEY, 60, Collections.emptyMap());

        verify(persister, never()).revokeToken(anyString(), anyLong());
        assertTrue(suo.contains("token-a" + SingleUseObjectProvider.REVOKED_KEY));
    }

    @Test
    void persistRevokedTokensTrueWritesThrough() {
        RedisConnectionProvider redis = provider();
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(RedisConnectionProvider.class)).thenReturn(redis);
        RevokedTokenPersisterProvider persister = mock(RevokedTokenPersisterProvider.class);
        when(session.getProvider(RevokedTokenPersisterProvider.class)).thenReturn(persister);

        RedisSingleUseObjectProvider suo = new RedisSingleUseObjectProvider(session, true);
        suo.put("token-b" + SingleUseObjectProvider.REVOKED_KEY, 120, Collections.emptyMap());

        verify(persister).revokeToken(eq("token-b"), eq(120L));
        assertTrue(suo.contains("token-b" + SingleUseObjectProvider.REVOKED_KEY));
    }
}
