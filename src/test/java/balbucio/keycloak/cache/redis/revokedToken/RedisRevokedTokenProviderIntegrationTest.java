package balbucio.keycloak.cache.redis.revokedToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import balbucio.keycloak.cache.redis.integration.AbstractRedisIntegrationTest;
import balbucio.keycloak.cache.redis.singleUseObject.RedisSingleUseObjectProvider;
import balbucio.keycloak.cache.redis.singleUseObject.SingleUseObjectKey;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.session.RevokedTokenPersisterProvider;

class RedisRevokedTokenProviderIntegrationTest extends AbstractRedisIntegrationTest {

    private RedisRevokedTokenProvider newProvider(KeycloakSession session, boolean persistRevokedTokens) {
        RedisSingleUseObjectProvider suo = new RedisSingleUseObjectProvider(session, persistRevokedTokens);
        return new RedisRevokedTokenProvider(suo);
    }

    /**
     * The provider maps to single-use entries keyed {@code id + REVOKED_KEY} (same layout as the
     * stock Infinispan implementation), so {@code put} is atomic SET-NX ("newly revoked?") and
     * entries expire via TTL without any manual cleanup.
     */
    @Test
    void putIsAtomicAndContainsUsesRevokedKeyEntry() {
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(RedisConnectionProvider.class)).thenReturn(provider());

        RedisRevokedTokenProvider revoked = newProvider(session, false);

        assertTrue(revoked.put("jti-1", 60), "first revoke must be new");
        assertFalse(revoked.put("jti-1", 60), "second revoke of the same jti is not new");
        assertTrue(revoked.contains("jti-1"));
        assertFalse(revoked.contains("jti-other"));

        RedisConnectionProvider conn = provider();
        String entryKey = SingleUseObjectKey.of("jti-1" + SingleUseObjectProvider.REVOKED_KEY).key();
        assertEquals(1L, conn.sync().exists(entryKey));
        Long pttl = conn.sync().pttl(entryKey);
        assertTrue(pttl != null && pttl > 0 && pttl <= 60_000L, "entry must expire via TTL, got " + pttl);
        assertTrue(
                entryKey.endsWith("jti-1" + SingleUseObjectProvider.REVOKED_KEY),
                "entry must be keyed <jti> + REVOKED_KEY, got " + entryKey);
    }

    @Test
    void putWritesThroughToJpaWhenPersistRevokedTokensEnabled() {
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(RedisConnectionProvider.class)).thenReturn(provider());
        RevokedTokenPersisterProvider persister = mock(RevokedTokenPersisterProvider.class);
        when(session.getProvider(RevokedTokenPersisterProvider.class)).thenReturn(persister);

        RedisRevokedTokenProvider revoked = newProvider(session, true);
        assertTrue(revoked.put("jti-2", 90));

        verify(persister).revokeToken("jti-2", 90L);
        assertTrue(revoked.contains("jti-2"));
    }

    @Test
    void distinctJtisAreIndependent() {
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(RedisConnectionProvider.class)).thenReturn(provider());

        RedisRevokedTokenProvider revoked = newProvider(session, false);
        assertTrue(revoked.put("jti-a", 300));
        assertTrue(revoked.put("jti-b", 300));
        assertTrue(revoked.contains("jti-a"));
        assertTrue(revoked.contains("jti-b"));
        // Sanity: raw single-use put of a revoked entry is visible to the provider (shared area).
        RedisSingleUseObjectProvider suo = new RedisSingleUseObjectProvider(session, false);
        suo.put("jti-c" + SingleUseObjectProvider.REVOKED_KEY, 300, Collections.emptyMap());
        assertTrue(revoked.contains("jti-c"));
    }
}
