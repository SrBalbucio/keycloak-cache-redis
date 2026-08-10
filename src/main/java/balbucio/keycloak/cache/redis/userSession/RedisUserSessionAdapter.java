package balbucio.keycloak.cache.redis.userSession;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import balbucio.keycloak.cache.redis.MapEntity;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.ExpirationUtils;
import balbucio.keycloak.cache.redis.common.ModelIllegalStateException;
import balbucio.keycloak.cache.redis.common.TimeAdapter;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;

public class RedisUserSessionAdapter extends MapEntity implements UserSessionModel {

    static final String REALM_ID = "realmId";
    static final String USER_ID = "userId";
    static final String LOGIN_USERNAME = "loginUsername";
    static final String IP_ADDRESS = "ipAddress";
    static final String AUTH_METHOD = "authMethod";
    static final String REMEMBER_ME = "rememberMe";
    static final String STARTED = "started";
    static final String LAST_SESSION_REFRESH = "lastSessionRefresh";
    static final String OFFLINE = "offline";
    static final String BROKER_SESSION_ID = "brokerSessionId";
    static final String BROKER_USER_ID = "brokerUserId";
    static final String STATE = "state";
    static final String PERSISTENCE_STATE = "persistenceState";
    static final String ID = "id";

    private final KeycloakSession session;
    private final RedisUserSessionProvider provider;
    private final UserSessionKey key;
    private SessionPersistenceState persistenceState = SessionPersistenceState.PERSISTENT;

    public RedisUserSessionAdapter(
            KeycloakSession session, RedisUserSessionProvider provider, UserSessionKey key, MapEntity entity) {
        this.session = session;
        this.provider = provider;
        this.key = key;
        if (entity != null) {
            copyFrom(entity);
        }
        String ps = get(PERSISTENCE_STATE);
        if (ps != null) {
            this.persistenceState = SessionPersistenceState.fromString(ps);
        }
    }

    public UserSessionKey getKey() {
        return key;
    }

    private void check() {
        if (isMarkedForDelete()) {
            throw new ModelIllegalStateException("User session " + key.id() + " was deleted");
        }
    }

    @Override
    public String getId() {
        return key.id();
    }

    @Override
    public RealmModel getRealm() {
        check();
        return session.realms().getRealm(get(REALM_ID));
    }

    @Override
    public String getBrokerSessionId() {
        check();
        return get(BROKER_SESSION_ID);
    }

    @Override
    public String getBrokerUserId() {
        check();
        return get(BROKER_USER_ID);
    }

    @Override
    public UserModel getUser() {
        check();
        return session.users().getUserById(getRealm(), get(USER_ID));
    }

    @Override
    public String getLoginUsername() {
        check();
        return get(LOGIN_USERNAME);
    }

    @Override
    public String getIpAddress() {
        check();
        return get(IP_ADDRESS);
    }

    @Override
    public String getAuthMethod() {
        check();
        return get(AUTH_METHOD);
    }

    @Override
    public boolean isRememberMe() {
        check();
        return Boolean.TRUE.equals(TimeAdapter.parseBoolean(get(REMEMBER_ME)));
    }

    @Override
    public int getStarted() {
        check();
        Integer started = TimeAdapter.parseInt(get(STARTED));
        return started == null ? 0 : started;
    }

    @Override
    public int getLastSessionRefresh() {
        check();
        Integer value = TimeAdapter.parseInt(get(LAST_SESSION_REFRESH));
        return value == null ? 0 : value;
    }

    @Override
    public void setLastSessionRefresh(int seconds) {
        check();
        set(LAST_SESSION_REFRESH, Integer.toString(seconds));
        updateExpiration();
    }

    @Override
    public boolean isOffline() {
        return key.offline();
    }

    @Override
    public Map<String, AuthenticatedClientSessionModel> getAuthenticatedClientSessions() {
        check();
        Map<String, AuthenticatedClientSessionModel> result = new HashMap<>();
        RealmModel realm = getRealm();
        for (String clientId : getMap(Constants.CLIENT_SESSION_PREFIX).keySet()) {
            ClientModel client = realm.getClientById(clientId);
            if (client == null) {
                continue;
            }
            AuthenticatedClientSessionModel clientSession = provider.getClientSession(this, client, isOffline());
            if (clientSession != null) {
                result.put(clientId, clientSession);
            }
        }
        return result;
    }

    @Override
    public void removeAuthenticatedClientSessions(Collection<String> removedClientUUIDS) {
        check();
        if (removedClientUUIDS == null) {
            return;
        }
        for (String clientId : removedClientUUIDS) {
            removeMapEntry(Constants.CLIENT_SESSION_PREFIX, clientId);
            provider.removeClientSession(key.realmId(), getId(), clientId, isOffline());
        }
    }

    @Override
    public String getNote(String name) {
        check();
        return getMap(Constants.NOTE_PREFIX).get(name);
    }

    @Override
    public void setNote(String name, String value) {
        check();
        if (value == null) {
            removeNote(name);
        } else {
            putMapEntry(Constants.NOTE_PREFIX, name, value);
        }
    }

    @Override
    public void removeNote(String name) {
        check();
        removeMapEntry(Constants.NOTE_PREFIX, name);
    }

    @Override
    public Map<String, String> getNotes() {
        check();
        return getMap(Constants.NOTE_PREFIX);
    }

    @Override
    public State getState() {
        check();
        String state = get(STATE);
        if (state == null) {
            return null;
        }
        return State.valueOf(state);
    }

    @Override
    public void setState(State state) {
        check();
        if (state == null) {
            remove(STATE);
        } else {
            set(STATE, state.name());
        }
    }

    @Override
    public void restartSession(
            RealmModel realm,
            UserModel user,
            String loginUsername,
            String ipAddress,
            String authMethod,
            boolean rememberMe,
            String brokerSessionId,
            String brokerUserId) {
        check();
        set(REALM_ID, realm.getId());
        set(USER_ID, user.getId());
        set(LOGIN_USERNAME, loginUsername);
        set(IP_ADDRESS, ipAddress);
        set(AUTH_METHOD, authMethod);
        set(REMEMBER_ME, Boolean.toString(rememberMe));
        set(BROKER_SESSION_ID, brokerSessionId);
        set(BROKER_USER_ID, brokerUserId);
        int now = Time.currentTime();
        set(STARTED, Integer.toString(now));
        set(LAST_SESSION_REFRESH, Integer.toString(now));
        setState(null);
        for (String note : Map.copyOf(getMap(Constants.NOTE_PREFIX)).keySet()) {
            removeMapEntry(Constants.NOTE_PREFIX, note);
        }
        for (String clientId : Map.copyOf(getMap(Constants.CLIENT_SESSION_PREFIX)).keySet()) {
            removeMapEntry(Constants.CLIENT_SESSION_PREFIX, clientId);
            provider.removeClientSession(key.realmId(), getId(), clientId, isOffline());
        }
        updateExpiration();
    }

    @Override
    public SessionPersistenceState getPersistenceState() {
        return persistenceState == null ? SessionPersistenceState.PERSISTENT : persistenceState;
    }

    public void setPersistenceState(SessionPersistenceState persistenceState) {
        this.persistenceState = persistenceState;
        set(PERSISTENCE_STATE, persistenceState.name());
    }

    public void addClientSession(String clientId) {
        putMapEntry(Constants.CLIENT_SESSION_PREFIX, clientId, "1");
    }

    public void updateExpiration() {
        String realmId = get(REALM_ID);
        if (realmId == null) {
            return;
        }
        RealmModel realm = session.realms().getRealm(realmId);
        if (realm != null) {
            setExpiration(ExpirationUtils.userSessionExpireAtMillis(realm, this));
        }
    }

    public static RedisUserSessionAdapter createNew(
            KeycloakSession session,
            RedisUserSessionProvider provider,
            UserSessionKey key,
            RealmModel realm,
            UserModel user,
            String loginUsername,
            String ipAddress,
            String authMethod,
            boolean rememberMe,
            String brokerSessionId,
            String brokerUserId,
            SessionPersistenceState persistenceState) {

        MapEntity entity = MapEntity.createNew();
        entity.set(ID, key.id());
        entity.set(REALM_ID, realm.getId());
        entity.set(USER_ID, user.getId());
        entity.set(LOGIN_USERNAME, loginUsername);
        entity.set(IP_ADDRESS, ipAddress);
        entity.set(AUTH_METHOD, authMethod);
        entity.set(REMEMBER_ME, Boolean.toString(rememberMe));
        entity.set(OFFLINE, Boolean.toString(key.offline()));
        entity.set(BROKER_SESSION_ID, brokerSessionId);
        entity.set(BROKER_USER_ID, brokerUserId);
        int now = Time.currentTime();
        entity.set(STARTED, Integer.toString(now));
        entity.set(LAST_SESSION_REFRESH, Integer.toString(now));
        entity.set(PERSISTENCE_STATE, persistenceState.name());

        RedisUserSessionAdapter adapter = new RedisUserSessionAdapter(session, provider, key, entity);
        adapter.setPersistenceState(persistenceState);
        adapter.updateExpiration();
        return adapter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSessionModel that)) {
            return false;
        }
        return Objects.equals(getId(), that.getId()) && isOffline() == that.isOffline();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), isOffline());
    }
}
