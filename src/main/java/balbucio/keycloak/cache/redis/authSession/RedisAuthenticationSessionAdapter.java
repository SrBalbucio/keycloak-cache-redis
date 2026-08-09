package balbucio.keycloak.cache.redis.authSession;

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

    public RedisAuthenticationSessionAdapter(
            KeycloakSession session, RedisRootAuthenticationSessionAdapter parent, String tabId) {
        this.session = session;
        this.parent = parent;
        this.tabId = tabId;
    }

    String getClientUuid() {
        return parent.getTabField(tabId, "clientUUID");
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
        for (Map.Entry<String, String> e : parent.getTabMap(tabId, EXECUTION_PREFIX).entrySet()) {
            result.put(e.getKey(), ExecutionStatus.valueOf(e.getValue()));
        }
        return result;
    }

    @Override
    public void setExecutionStatus(String authenticator, ExecutionStatus status) {
        Objects.requireNonNull(authenticator);
        Objects.requireNonNull(status);
        parent.putTabMap(tabId, EXECUTION_PREFIX, authenticator, status.name());
        touch();
    }

    @Override
    public void clearExecutionStatus() {
        parent.clearTabMap(tabId, EXECUTION_PREFIX);
        touch();
    }

    @Override
    public UserModel getAuthenticatedUser() {
        String userId = parent.getTabField(tabId, "authUserId");
        if (userId == null) {
            return null;
        }
        return session.users().getUserById(getRealm(), userId);
    }

    @Override
    public void setAuthenticatedUser(UserModel user) {
        parent.setTabField(tabId, "authUserId", user == null ? null : user.getId());
        touch();
    }

    @Override
    public Set<String> getRequiredActions() {
        return new HashSet<>(parent.getTabMap(tabId, REQUIRED_ACTION_PREFIX).keySet());
    }

    @Override
    public void addRequiredAction(String action) {
        Objects.requireNonNull(action);
        parent.putTabMap(tabId, REQUIRED_ACTION_PREFIX, action, "1");
        touch();
    }

    @Override
    public void removeRequiredAction(String action) {
        Objects.requireNonNull(action);
        parent.putTabMap(tabId, REQUIRED_ACTION_PREFIX, action, null);
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
        parent.putTabMap(tabId, USER_NOTE_PREFIX, name, value);
        touch();
    }

    @Override
    public Map<String, String> getUserSessionNotes() {
        return parent.getTabMap(tabId, USER_NOTE_PREFIX);
    }

    @Override
    public void clearUserSessionNotes() {
        parent.clearTabMap(tabId, USER_NOTE_PREFIX);
        touch();
    }

    @Override
    public String getAuthNote(String name) {
        if (name == null) {
            return null;
        }
        return parent.getTabMap(tabId, AUTH_NOTE_PREFIX).get(name);
    }

    @Override
    public void setAuthNote(String name, String value) {
        if (name == null || value == null) {
            return;
        }
        parent.putTabMap(tabId, AUTH_NOTE_PREFIX, name, value);
        touch();
    }

    @Override
    public void removeAuthNote(String name) {
        if (name == null) {
            return;
        }
        parent.putTabMap(tabId, AUTH_NOTE_PREFIX, name, null);
        touch();
    }

    @Override
    public void clearAuthNotes() {
        parent.clearTabMap(tabId, AUTH_NOTE_PREFIX);
        touch();
    }

    @Override
    public String getClientNote(String name) {
        if (name == null) {
            return null;
        }
        return parent.getTabMap(tabId, CLIENT_NOTE_PREFIX).get(name);
    }

    @Override
    public void setClientNote(String name, String value) {
        if (name == null || value == null) {
            return;
        }
        parent.putTabMap(tabId, CLIENT_NOTE_PREFIX, name, value);
        touch();
    }

    @Override
    public void removeClientNote(String name) {
        if (name == null) {
            return;
        }
        parent.putTabMap(tabId, CLIENT_NOTE_PREFIX, name, null);
        touch();
    }

    @Override
    public Map<String, String> getClientNotes() {
        return parent.getTabMap(tabId, CLIENT_NOTE_PREFIX);
    }

    @Override
    public void clearClientNotes() {
        parent.clearTabMap(tabId, CLIENT_NOTE_PREFIX);
        touch();
    }

    @Override
    public Set<String> getClientScopes() {
        return new HashSet<>(parent.getTabMap(tabId, CLIENT_SCOPE_PREFIX).keySet());
    }

    @Override
    public void setClientScopes(Set<String> clientScopes) {
        Objects.requireNonNull(clientScopes);
        parent.clearTabMap(tabId, CLIENT_SCOPE_PREFIX);
        for (String scope : clientScopes) {
            parent.putTabMap(tabId, CLIENT_SCOPE_PREFIX, scope, "1");
        }
        touch();
    }

    @Override
    public String getRedirectUri() {
        return parent.getTabField(tabId, "redirectUri");
    }

    @Override
    public void setRedirectUri(String uri) {
        parent.setTabField(tabId, "redirectUri", uri);
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
        return parent.getTabField(tabId, "action");
    }

    @Override
    public void setAction(String action) {
        parent.setTabField(tabId, "action", action);
        touch();
    }

    @Override
    public String getProtocol() {
        return parent.getTabField(tabId, "protocol");
    }

    @Override
    public void setProtocol(String method) {
        parent.setTabField(tabId, "protocol", method);
        touch();
    }

    private void touch() {
        parent.setTabField(tabId, "timestamp", Integer.toString(Time.currentTime()));
        parent.setTimestamp(Time.currentTime());
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
