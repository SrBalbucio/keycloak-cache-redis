package balbucio.keycloak.cache.redis.compatibility;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;
import org.keycloak.keys.PublicKeyLoader;
import org.keycloak.keys.PublicKeyStorageProvider;

/**
 * In-memory public key storage replacing the Infinispan-backed provider.
 */
public class MapPublicKeyStorageProvider implements PublicKeyStorageProvider {

    private final Map<String, PublicKeysWrapper> keys = new ConcurrentHashMap<>();

    @Override
    public KeyWrapper getPublicKey(String modelKey, String kid, String algorithm, PublicKeyLoader loader) {
        PublicKeysWrapper wrapper = getOrLoad(modelKey, loader);
        return wrapper.getKeyByKidAndAlg(kid, algorithm);
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, String algorithm, PublicKeyLoader loader) {
        PublicKeysWrapper wrapper = getOrLoad(modelKey, loader);
        return wrapper.getKeyByKidAndAlg(null, algorithm);
    }

    @Override
    public KeyWrapper getFirstPublicKey(String modelKey, Predicate<KeyWrapper> predicate, PublicKeyLoader loader) {
        PublicKeysWrapper wrapper = getOrLoad(modelKey, loader);
        return wrapper.getKeys().stream().filter(predicate).findFirst().orElse(null);
    }

    @Override
    public List<KeyWrapper> getKeys(String modelKey, PublicKeyLoader loader) {
        return getOrLoad(modelKey, loader).getKeys();
    }

    @Override
    public boolean reloadKeys(String modelKey, PublicKeyLoader loader) {
        try {
            PublicKeysWrapper loaded = loader.loadKeys();
            if (loaded == null) {
                loaded = PublicKeysWrapper.EMPTY;
            }
            keys.put(modelKey, loaded);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reload public keys for " + modelKey, e);
        }
    }

    private PublicKeysWrapper getOrLoad(String modelKey, PublicKeyLoader loader) {
        PublicKeysWrapper cached = keys.get(modelKey);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = keys.get(modelKey);
            if (cached != null) {
                return cached;
            }
            try {
                PublicKeysWrapper loaded = loader.loadKeys();
                if (loaded == null) {
                    loaded = PublicKeysWrapper.EMPTY;
                }
                keys.put(modelKey, loaded);
                return loaded;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load public keys for " + modelKey, e);
            }
        }
    }

    @Override
    public void close() {
        keys.clear();
    }
}
