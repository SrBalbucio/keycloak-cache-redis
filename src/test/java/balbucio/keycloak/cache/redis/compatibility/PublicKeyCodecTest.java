package balbucio.keycloak.cache.redis.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keycloak.crypto.KeyStatus;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;

class PublicKeyCodecTest {

    @Test
    void roundTripsPublicKeyThroughJson() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyWrapper key = new KeyWrapper();
        key.setKid("kid-1");
        key.setAlgorithm("RS256");
        key.setType("RSA");
        key.setUse(KeyUse.SIG);
        key.setStatus(KeyStatus.ACTIVE);
        key.setProviderId("provider");
        key.setProviderPriority(10);
        key.setPublicKey(pair.getPublic());

        PublicKeysWrapper wrapper = new PublicKeysWrapper(List.of(key), 12345L);
        CachedPublicKeys cached = PublicKeyCodec.toCached(wrapper);

        String json = new ObjectMapper().writeValueAsString(cached);
        CachedPublicKeys back = new ObjectMapper().readValue(json, CachedPublicKeys.class);
        PublicKeysWrapper restored = PublicKeyCodec.fromCached(back);

        assertEquals(12345L, restored.getExpirationTime());
        assertEquals(1, restored.getKeys().size());
        KeyWrapper restoredKey = restored.getKeys().get(0);
        assertEquals("kid-1", restoredKey.getKid());
        assertEquals("RS256", restoredKey.getAlgorithm());
        assertEquals(KeyUse.SIG, restoredKey.getUse());
        assertNotNull(restoredKey.getPublicKey());
        assertEquals(pair.getPublic(), restoredKey.getPublicKey());
    }
}
