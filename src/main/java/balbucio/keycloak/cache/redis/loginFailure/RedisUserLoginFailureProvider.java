package balbucio.keycloak.cache.redis.loginFailure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import balbucio.keycloak.cache.redis.RedisChangelogTransaction;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserLoginFailureProvider;

public class RedisUserLoginFailureProvider implements UserLoginFailureProvider {

    private final RedisConnectionProvider connection;
    private final RedisChangelogTransaction<LoginFailureKey, RedisUserLoginFailureAdapter> tx;

    public RedisUserLoginFailureProvider(KeycloakSession session) {
        this.connection = session.getProvider(RedisConnectionProvider.class);
        this.tx =
                new RedisChangelogTransaction<>(
                        session,
                        connection,
                        (key, entity) -> new RedisUserLoginFailureAdapter(key, entity),
                        this::indexes);
    }

    @Override
    public UserLoginFailureModel getUserLoginFailure(RealmModel realm, String userId) {
        return tx.get(LoginFailureKey.of(realm.getId(), userId));
    }

    @Override
    public UserLoginFailureModel addUserLoginFailure(RealmModel realm, String userId) {
        LoginFailureKey key = LoginFailureKey.of(realm.getId(), userId);
        RedisUserLoginFailureAdapter existing = tx.get(key);
        if (existing != null) {
            return existing;
        }
        RedisUserLoginFailureAdapter created = RedisUserLoginFailureAdapter.createNew(key);
        applyExpiration(realm, created);
        tx.create(key, created);
        return created;
    }

    @Override
    public void removeUserLoginFailure(RealmModel realm, String userId) {
        tx.delete(LoginFailureKey.of(realm.getId(), userId));
    }

    @Override
    public void removeAllUserLoginFailures(RealmModel realm) {
        Set<String> members = connection.sync().smembers(LoginFailureIndexes.realmIndex(realm.getId()));
        if (members == null) {
            return;
        }
        for (String userId : members) {
            tx.delete(LoginFailureKey.of(realm.getId(), userId));
        }
    }

    @Override
    public void updateWithLatestRealmSettings(RealmModel realm) {
        Set<String> members = connection.sync().smembers(LoginFailureIndexes.realmIndex(realm.getId()));
        if (members == null) {
            return;
        }
        for (String userId : members) {
            RedisUserLoginFailureAdapter adapter = tx.get(LoginFailureKey.of(realm.getId(), userId));
            if (adapter != null) {
                applyExpiration(realm, adapter);
            }
        }
    }

    @Override
    public void close() {}

    private void applyExpiration(RealmModel realm, RedisUserLoginFailureAdapter adapter) {
        int maxWait = realm.getMaxFailureWaitSeconds();
        int waitIncrement = realm.getWaitIncrementSeconds();
        int lifespan = Math.max(maxWait, waitIncrement);
        if (lifespan <= 0) {
            lifespan = 900;
        }
        // keep a bit beyond lockout window
        adapter.setExpiration(Time.currentTimeMillis() + (lifespan * 2L) * 1000L);
    }

    private Collection<RedisChangelogTransaction.IndexUpdate> indexes(RedisUserLoginFailureAdapter adapter) {
        List<RedisChangelogTransaction.IndexUpdate> list = new ArrayList<>();
        String realmId = adapter.get(RedisUserLoginFailureAdapter.REALM_ID);
        if (realmId != null) {
            list.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            LoginFailureIndexes.realmIndex(realmId), adapter.getUserId()));
        }
        return list;
    }
}
