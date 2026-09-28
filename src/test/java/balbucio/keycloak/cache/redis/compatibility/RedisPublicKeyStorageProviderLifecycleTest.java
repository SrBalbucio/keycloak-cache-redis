package balbucio.keycloak.cache.redis.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.keycloak.crypto.PublicKeysWrapper;

/**
 * Regressão da mesma classe do bug do ClusterProvider: {@code DefaultKeycloakSession.close()}
 * fecha providers criados na sessão, então {@code close()} aqui não pode limpar o L1
 * compartilhado da factory (antes, cada request zerava o L1 de public-keys do nó).
 */
class RedisPublicKeyStorageProviderLifecycleTest {

    @Test
    void sessionCloseKeepsSharedL1() {
        Map<String, PublicKeysWrapper> sharedL1 = new ConcurrentHashMap<>();
        sharedL1.put("model-1", PublicKeysWrapper.EMPTY);

        RedisPublicKeyStorageProvider provider =
                new RedisPublicKeyStorageProvider(
                        mock(RedisConnectionProvider.class), new ObjectMapper(), 3600L, sharedL1);
        provider.close();

        assertEquals(1, sharedL1.size(), "session close must not wipe the factory-shared L1");
        assertTrue(sharedL1.containsKey("model-1"));
    }
}
