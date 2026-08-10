package balbucio.keycloak.cache.redis.authSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.CommonClientSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

public class RedisAuthenticationSessionAdapter implements AuthenticationSessionModel {

    private static final String AUTH_NOTE_PREFIX = "an.";
    private static final String CLIENT_NOTE_PREFIX = "cn.";
    private static final String USER_NOTE_PREFIX = "un.";
    private static final String REQUIRED_ACTION_PREFIX = "ra.";
    private static final String EXECUTION_PREFIX = "es.";
    private static final String CLIENT_SCOPE_PREFIX = "cs.";

    private final KeycloakSession session;
    private final RedisRootAuthenticationSessionAdapter parent;
    private final String tabId;
    /**
     * Snapshot of this tab's fields (suffix -&gt; value), captured at construction and kept in sync
     * via write-through. This decouples reads from the live root entity so the adapter keeps
     * returning its values after Keycloak removes the tab mid-request (see
     * {@code TokenManager.attachAuthenticationSession} -&gt;
     * {@code updateAuthenticationSessionAfterSuccessfulAuthentication}, which deletes the tab and
     * then calls {@code authSession.getProtocol()} to build the success redirect). The stock
     * Infinispan adapter gets this for free because it holds a detached entity; this flat-hash
     * layout would otherwise return {@code null} once {@code removeTabFields} runs.
     */
    private final Map<String, String> fields;

    public RedisAuthenticationSessionAdapter(
            KeycloakSession session, RedisRootAuthenticationSessionAdapter parent, String tabId) {
        this.session = session;
        this.parent = parent;
        this.tabId = tabId;
        this.fields = new HashMap<>(parent.getTabMap(tabId, ""));
    }

    private String field(String name) {
        return fields.get(name);
    }

    private void field(String name, String value) {
        if (value == null) {
            fields.remove(name);
        } else {
            fields.put(name, value);
        }
        parent.setTabField(tabId, name, value);
    }

    private Map<String, String> fieldMap(String prefix) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                result.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return result;
    }

    private void mapPut(String prefix, String name, String value) {
        if (name == null) {
            return;
        }
        field(prefix + name, value);
    }

    private void mapClear(String prefix) {
        for (String key : new ArrayList<>(fields.keySet())) {
            if (key.startsWith(prefix)) {
                field(key, null);
            }
        }
    }

    private Set<String> mapKeys(String prefix) {
        return new HashSet<>(fieldMap(prefix).keySet());
    }

    String getClientUuid() {
        return field("clientUUID");
    }

    @Override
    public String getTabId() {
        return tabId;
    }

    @Override
    public RootAuthenticationSessionModel getParentSession() {
        return parent;
    }

    @Override
    public Map<String, ExecutionStatus> getExecutionStatus() {
        Map<String, ExecutionStatus> result = new HashMap<>();
        for (Map.Entry<String, String> e : fieldMap(EXECUTION_PREFIX).entrySet()) {
            result.put(e.getKey(), ExecutionStatus.valueOf(e.getValue()));
        }
        return result;
    }

    @Override
    public void setExecutionStatus(String authenticator, ExecutionStatus status) {
        Objects.requireNonNull(authenticator);
        Objects.requireNonNull(status);
        mapPut(EXECUTION_PREFIX, authenticator, status.name());
        touch();
    }

    @Override
    public void clearExecutionStatus() {
        mapClear(EXECUTION_PREFIX);
        touch();
    }

    @Override
    public UserModel getAuthenticatedUser() {
        String userId = field("authUserId");
        if (userId == null) {
            return null;
        }
        return session.users().getUserById(getRealm(), userId);
    }

    @Override
    public void setAuthenticatedUser(UserModel user) {
        field("authUserId", user == null ? null : user.getId());
        touch();
    }

    @Override
    public Set<String> getRequiredActions() {
        return mapKeys(REQUIRED_ACTION_PREFIX);
    }

    @Override
    public void addRequiredAction(String action) {
        Objects.requireNonNull(action);
        mapPut(REQUIRED_ACTION_PREFIX, action, "1");
        touch();
    }

    @Override
    public void removeRequiredAction(String action) {
        Objects.requireNonNull(action);
        mapPut(REQUIRED_ACTION_PREFIX, action, null);
        touch();
    }

    @Override
    public void addRequiredAction(UserModel.RequiredAction action) {
        addRequiredAction(action.name());
    }

    @Override
    public void removeRequiredAction(UserModel.RequiredAction action) {
        removeRequiredAction(action.name());
    }

    @Override
    public void setUserSessionNote(String name, String value) {
        if (name == null || value == null) {
            return;
        }
        mapPut(USER_NOTE_PREFIX, name, value);
        touch();
    }

    @Override
    public Map<String, String> getUserSessionNotes() {
        return fieldMap(USER_NOTE_PREFIX);
    }

    @Override
    public void clearUserSessionNotes() {
        mapClear(USER_NOTE_PREFIX);
        touch();
    }

    @Override
    public String getAuthNote(String name) {
        if (name == null) {
            return null;
        }
        return field(AUTH_NOTE_PREFIX + name);
    }

    @Override
    public void setAuthNote(String name, String value) {
        if (name == null || value == null) {
            return;
        }
        mapPut(AUTH_NOTE_PREFIX, name, value);
        touch();
    }

    @Override
    public void removeAuthNote(String name) {
        if (name == null) {
            return;
        }
        mapPut(AUTH_NOTE_PREFIX, name, null);
        touch();
    }

    @Override
    public void clearAuthNotes() {
        mapClear(AUTH_NOTE_PREFIX);
        touch();
    }

    @Override
    public String getClientNote(String name) {
        if (name == null) {
            return null;
        }
        return field(CLIENT_NOTE_PREFIX + name);
    }

    @Override
    public void setClientNote(String name, String value) {
        if (name == null || value == null) {
            return;
        }
        mapPut(CLIENT_NOTE_PREFIX, name, value);
        touch();
    }

    @Override
    public void removeClientNote(String name) {
        if (name == null) {
            return;
        }
        mapPut(CLIENT_NOTE_PREFIX, name, null);
        touch();
    }

    @Override
    public Map<String, String> getClientNotes() {
        return fieldMap(CLIENT_NOTE_PREFIX);
    }

    @Override
    public void clearClientNotes() {
        mapClear(CLIENT_NOTE_PREFIX);
        touch();
    }

    @Override
    public Set<String> getClientScopes() {
        return mapKeys(CLIENT_SCOPE_PREFIX);
    }

    @Override
    public void setClientScopes(Set<String> clientScopes) {
        Objects.requireNonNull(clientScopes);
        mapClear(CLIENT_SCOPE_PREFIX);
        for (String scope : clientScopes) {
            mapPut(CLIENT_SCOPE_PREFIX, scope, "1");
        }
        touch();
    }

    @Override
    public String getRedirectUri() {
        return field("redirectUri");
    }

    @Override
    public void setRedirectUri(String uri) {
        field("redirectUri", uri);
        touch();
    }

    @Override
    public RealmModel getRealm() {
        return parent.getRealm();
    }

    @Override
    public ClientModel getClient() {
        return getRealm().getClientById(getClientUuid());
    }

    @Override
    public String getAction() {
        return field("action");
    }

    @Override
    public void setAction(String action) {
        field("action", action);
        touch();
    }

    @Override
    public String getProtocol() {
        return field("protocol");
    }

    @Override
    public void setProtocol(String method) {
        field("protocol", method);
        touch();
    }

    private void touch() {
        int now = Time.currentTime();
        field("timestamp", Integer.toString(now));
        parent.setTimestamp(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommonClientSessionModel)) {
            return false;
        }
        if (!(o instanceof AuthenticationSessionModel that)) {
            return false;
        }
        return Objects.equals(tabId, that.getTabId())
                && Objects.equals(parent.getId(), that.getParentSession().getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(parent.getId(), tabId);
    }
}
