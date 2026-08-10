package balbucio.keycloak.cache.redis.compatibility;

import java.util.ArrayList;
import java.util.List;

/** Redis JSON envelope for a {@link org.keycloak.crypto.PublicKeysWrapper}. */
public class CachedPublicKeys {

    private Long expirationTime;
    private List<CachedPublicKey> keys = new ArrayList<>();

    public Long getExpirationTime() {
        return expirationTime;
    }

    public void setExpirationTime(Long expirationTime) {
        this.expirationTime = expirationTime;
    }

    public List<CachedPublicKey> getKeys() {
        return keys;
    }

    public void setKeys(List<CachedPublicKey> keys) {
        this.keys = keys != null ? keys : new ArrayList<>();
    }
}
