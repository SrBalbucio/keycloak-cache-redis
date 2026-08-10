package balbucio.keycloak.cache.redis.entity;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.storage.UserStorageManager;

/**
 * {@link UserCache} MVP: delegates to {@link UserStorageManager} and caches username/email → id
 * indexes in Redis (+ L1) for hot lookups.
 */
public class RedisUserCache extends UserStorageManager implements UserCache {

    private final RedisEntityIndexCache cache;

    public RedisUserCache(KeycloakSession session, RedisEntityIndexCache cache) {
        super(session);
        this.cache = cache;
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        return super.getUserById(realm, id);
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        if (username == null) {
            return null;
        }
        String key = RedisEntityIndexCache.userByUsername(realm.getId(), username);
        String cachedId = cache.get(key);
        if (cachedId != null) {
            UserModel user = getUserById(realm, cachedId);
            if (user != null) {
                return user;
            }
            cache.remove(key);
        }
        UserModel user = super.getUserByUsername(realm, username);
        if (user != null) {
            cache.put(key, user.getId());
            cache.put(RedisEntityIndexCache.userById(realm.getId(), user.getId()), user.getId());
        }
        return user;
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        if (email == null) {
            return null;
        }
        String key = RedisEntityIndexCache.userByEmail(realm.getId(), email);
        String cachedId = cache.get(key);
        if (cachedId != null) {
            UserModel user = getUserById(realm, cachedId);
            if (user != null) {
                return user;
            }
            cache.remove(key);
        }
        UserModel user = super.getUserByEmail(realm, email);
        if (user != null) {
            cache.put(key, user.getId());
            cache.put(RedisEntityIndexCache.userById(realm.getId(), user.getId()), user.getId());
        }
        return user;
    }

    @Override
    public void evict(RealmModel realm, UserModel user) {
        if (realm != null && user != null) {
            if (user.getUsername() != null) {
                cache.invalidate(RedisEntityIndexCache.userByUsername(realm.getId(), user.getUsername()));
            }
            if (user.getEmail() != null) {
                cache.invalidate(RedisEntityIndexCache.userByEmail(realm.getId(), user.getEmail()));
            }
            cache.invalidate(RedisEntityIndexCache.userById(realm.getId(), user.getId()));
        }
    }

    @Override
    public void evict(RealmModel realm) {
        cache.clearAllAndBroadcast();
    }

    @Override
    public void clear() {
        cache.clearAllAndBroadcast();
    }
}
