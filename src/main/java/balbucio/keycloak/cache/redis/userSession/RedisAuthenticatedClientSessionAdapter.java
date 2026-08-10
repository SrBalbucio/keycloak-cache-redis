package balbucio.keycloak.cache.redis.userSession;

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
import org.keycloak.models.UserSessionModel;
import org.keycloak.sessions.CommonClientSessionModel;

public class RedisAuthenticatedClientSessionAdapter extends MapEntity implements AuthenticatedClientSessionModel {

    static final String ID = "id";
    static final String REALM_ID = "realmId";
    static final String CLIENT_ID = "clientId";
    static final String USER_SESSION_ID = "userSessionId";
    static final String TIMESTAMP = "timestamp";
    static final String REDIRECT_URI = "redirectUri";
    static final String ACTION = "action";
    static final String PROTOCOL = "protocol";
    static final String OFFLINE = "offline";

    private final KeycloakSession session;
    private final RedisUserSessionProvider provider;
    private final AuthenticatedClientSessionKey key;
    private UserSessionModel userSession;

    public RedisAuthenticatedClientSessionAdapter(
            KeycloakSession session,
            RedisUserSessionProvider provider,
            AuthenticatedClientSessionKey key,
            MapEntity entity,
            UserSessionModel userSession) {
        this.session = session;
        this.provider = provider;
        this.key = key;
        this.userSession = userSession;
        if (entity != null) {
            copyFrom(entity);
        }
    }

    public AuthenticatedClientSessionKey getKey() {
        return key;
    }

    private void check() {
        if (isMarkedForDelete()) {
            throw new ModelIllegalStateException("Client session " + key.compoundId() + " was deleted");
        }
    }

    @Override
    public String getId() {
        return key.compoundId();
    }

    @Override
    public int getTimestamp() {
        check();
        Integer ts = TimeAdapter.parseInt(get(TIMESTAMP));
        return ts == null ? 0 : ts;
    }

    @Override
    public void setTimestamp(int timestamp) {
        check();
        set(TIMESTAMP, Integer.toString(timestamp));
        updateExpiration();
    }

    @Override
    public void detachFromUserSession() {
        check();
        if (userSession instanceof RedisUserSessionAdapter adapter) {
            adapter.removeMapEntry(Constants.CLIENT_SESSION_PREFIX, key.clientId());
        }
        provider.removeClientSession(key.realmId(), key.userSessionId(), key.clientId(), key.offline());
        this.userSession = null;
    }

    @Override
    public UserSessionModel getUserSession() {
        check();
        if (userSession == null) {
            userSession = provider.getUserSessionById(key.realmId(), key.userSessionId(), key.offline());
        }
        return userSession;
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
    public String getRedirectUri() {
        check();
        return get(REDIRECT_URI);
    }

    @Override
    public void setRedirectUri(String uri) {
        check();
        set(REDIRECT_URI, uri);
    }

    @Override
    public RealmModel getRealm() {
        check();
        return session.realms().getRealm(get(REALM_ID));
    }

    @Override
    public ClientModel getClient() {
        check();
        return getRealm().getClientById(key.clientId());
    }

    @Override
    public String getAction() {
        check();
        return get(ACTION);
    }

    @Override
    public void setAction(String action) {
        check();
        set(ACTION, action);
    }

    @Override
    public String getProtocol() {
        check();
        return get(PROTOCOL);
    }

    @Override
    public void setProtocol(String method) {
        check();
        set(PROTOCOL, method);
    }

    public void updateExpiration() {
        UserSessionModel us = getUserSession();
        RealmModel realm = getRealm();
        if (us != null && realm != null) {
            setExpiration(ExpirationUtils.clientSessionExpireAtMillis(realm, us, getTimestamp()));
        }
    }

    public static RedisAuthenticatedClientSessionAdapter createNew(
            KeycloakSession session,
            RedisUserSessionProvider provider,
            AuthenticatedClientSessionKey key,
            RealmModel realm,
            ClientModel client,
            UserSessionModel userSession) {

        MapEntity entity = MapEntity.createNew();
        entity.set(ID, key.compoundId());
        entity.set(REALM_ID, realm.getId());
        entity.set(CLIENT_ID, client.getId());
        entity.set(USER_SESSION_ID, userSession.getId());
        entity.set(OFFLINE, Boolean.toString(key.offline()));
        entity.set(TIMESTAMP, Integer.toString(Time.currentTime()));

        RedisAuthenticatedClientSessionAdapter adapter =
                new RedisAuthenticatedClientSessionAdapter(session, provider, key, entity, userSession);
        adapter.setNote(
                AuthenticatedClientSessionModel.STARTED_AT_NOTE, Integer.toString(Time.currentTime()));
        adapter.setNote(
                AuthenticatedClientSessionModel.USER_SESSION_STARTED_AT_NOTE,
                Integer.toString(userSession.getStarted()));
        if (userSession.isRememberMe()) {
            adapter.setNote(AuthenticatedClientSessionModel.USER_SESSION_REMEMBER_ME_NOTE, "true");
        }
        adapter.updateExpiration();
        return adapter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommonClientSessionModel that)) {
            return false;
        }
        return Objects.equals(getId(), ((AuthenticatedClientSessionModel) that).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }
}
