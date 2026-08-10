package balbucio.keycloak.cache.redis.loginFailure;

import balbucio.keycloak.cache.redis.MapEntity;
import balbucio.keycloak.cache.redis.common.TimeAdapter;
import org.keycloak.common.util.Time;
import org.keycloak.models.UserLoginFailureModel;

public class RedisUserLoginFailureAdapter extends MapEntity implements UserLoginFailureModel {

    static final String ID = "id";
    static final String REALM_ID = "realmId";
    static final String USER_ID = "userId";
    static final String FAILED_LOGIN_NOT_BEFORE = "failedLoginNotBefore";
    static final String NUM_FAILURES = "numFailures";
    static final String NUM_TEMPORARY_LOCKOUTS = "numTemporaryLockouts";
    static final String LAST_FAILURE = "lastFailure";
    static final String LAST_IP_FAILURE = "lastIPFailure";
    static final String NUM_SECONDARY_AUTH_FAILURES = "numSecondaryAuthFailures";

    private final LoginFailureKey key;

    public RedisUserLoginFailureAdapter(LoginFailureKey key, MapEntity entity) {
        this.key = key;
        if (entity != null) {
            copyFrom(entity);
        }
    }

    public LoginFailureKey getKey() {
        return key;
    }

    @Override
    public String getId() {
        return key.realmId() + ":" + key.userId();
    }

    @Override
    public String getUserId() {
        return key.userId();
    }

    @Override
    public int getFailedLoginNotBefore() {
        Integer v = TimeAdapter.parseInt(get(FAILED_LOGIN_NOT_BEFORE));
        return v == null ? 0 : v;
    }

    @Override
    public void setFailedLoginNotBefore(int notBefore) {
        set(FAILED_LOGIN_NOT_BEFORE, Integer.toString(notBefore));
    }

    @Override
    public int getNumFailures() {
        Integer v = TimeAdapter.parseInt(get(NUM_FAILURES));
        return v == null ? 0 : v;
    }

    @Override
    public void incrementFailures() {
        increment(NUM_FAILURES, 1);
        set(LAST_FAILURE, Long.toString(Time.currentTimeMillis()));
    }

    @Override
    public int getNumTemporaryLockouts() {
        Integer v = TimeAdapter.parseInt(get(NUM_TEMPORARY_LOCKOUTS));
        return v == null ? 0 : v;
    }

    @Override
    public void incrementTemporaryLockouts() {
        increment(NUM_TEMPORARY_LOCKOUTS, 1);
    }

    @Override
    public void clearFailures() {
        set(NUM_FAILURES, "0");
        set(NUM_TEMPORARY_LOCKOUTS, "0");
        set(NUM_SECONDARY_AUTH_FAILURES, "0");
        set(FAILED_LOGIN_NOT_BEFORE, "0");
        set(LAST_FAILURE, "0");
        remove(LAST_IP_FAILURE);
    }

    @Override
    public long getLastFailure() {
        Long v = TimeAdapter.parseLong(get(LAST_FAILURE));
        return v == null ? 0L : v;
    }

    @Override
    public void setLastFailure(long lastFailure) {
        set(LAST_FAILURE, Long.toString(lastFailure));
    }

    @Override
    public String getLastIPFailure() {
        return get(LAST_IP_FAILURE);
    }

    @Override
    public void setLastIPFailure(String ip) {
        set(LAST_IP_FAILURE, ip);
    }

    @Override
    public int getNumSecondaryAuthFailures() {
        Integer v = TimeAdapter.parseInt(get(NUM_SECONDARY_AUTH_FAILURES));
        return v == null ? 0 : v;
    }

    @Override
    public void incrementSecondaryAuthFailures() {
        increment(NUM_SECONDARY_AUTH_FAILURES, 1);
    }

    @Override
    public void clearPrimaryAndSecondaryAuthFailures() {
        set(NUM_FAILURES, "0");
        set(NUM_SECONDARY_AUTH_FAILURES, "0");
        set(FAILED_LOGIN_NOT_BEFORE, "0");
        set(LAST_FAILURE, "0");
        remove(LAST_IP_FAILURE);
    }

    public static RedisUserLoginFailureAdapter createNew(LoginFailureKey key) {
        MapEntity entity = MapEntity.createNew();
        entity.set(ID, key.realmId() + ":" + key.userId());
        entity.set(REALM_ID, key.realmId());
        entity.set(USER_ID, key.userId());
        entity.set(NUM_FAILURES, "0");
        entity.set(NUM_TEMPORARY_LOCKOUTS, "0");
        entity.set(NUM_SECONDARY_AUTH_FAILURES, "0");
        entity.set(FAILED_LOGIN_NOT_BEFORE, "0");
        entity.set(LAST_FAILURE, "0");
        return new RedisUserLoginFailureAdapter(key, entity);
    }
}
