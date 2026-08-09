package balbucio.keycloak.cache.redis.authSession;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import balbucio.keycloak.cache.redis.MapEntity;
import balbucio.keycloak.cache.redis.common.TimeAdapter;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

public class RedisRootAuthenticationSessionAdapter extends MapEntity implements RootAuthenticationSessionModel {

    static final String ID = "id";
    static final String REALM_ID = "realmId";
    static final String TIMESTAMP = "timestamp";
    static final String TAB_PREFIX = "t.";
    static final String TAB_EXISTS_SUFFIX = ".exists";

    private final KeycloakSession session;
    private final RedisAuthenticationSessionProvider provider;
    private final RootAuthenticationSessionKey key;
    private final RealmModel realm;
    private final int authSessionsLimit;

    public RedisRootAuthenticationSessionAdapter(
            KeycloakSession session,
            RedisAuthenticationSessionProvider provider,
            RootAuthenticationSessionKey key,
            RealmModel realm,
            MapEntity entity,
            int authSessionsLimit) {
        this.session = session;
        this.provider = provider;
        this.key = key;
        this.realm = realm;
        this.authSessionsLimit = authSessionsLimit;
        if (entity != null) {
            copyFrom(entity);
        }
    }

    public RootAuthenticationSessionKey getKey() {
        return key;
    }

    @Override
    public String getId() {
        return key.id();
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    @Override
    public int getTimestamp() {
        Integer ts = TimeAdapter.parseInt(get(TIMESTAMP));
        return ts == null ? 0 : ts;
    }

    @Override
    public void setTimestamp(int timestamp) {
        set(TIMESTAMP, Integer.toString(timestamp));
        updateExpiration();
    }

    @Override
    public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
        Map<String, AuthenticationSessionModel> result = new HashMap<>();
        for (String tabId : tabIds()) {
            result.put(tabId, new RedisAuthenticationSessionAdapter(session, this, tabId));
        }
        return result;
    }

    @Override
    public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
        if (client == null || tabId == null) {
            return null;
        }
        if (!hasTab(tabId)) {
            return null;
        }
        RedisAuthenticationSessionAdapter authSession = new RedisAuthenticationSessionAdapter(session, this, tabId);
        if (!Objects.equals(client.getId(), authSession.getClientUuid())) {
            return null;
        }
        session.getContext().setAuthenticationSession(authSession);
        return authSession;
    }

    @Override
    public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
        Objects.requireNonNull(client, "client");

        Set<String> tabs = tabIds();
        if (tabs.size() >= authSessionsLimit) {
            String oldest =
                    tabs.stream()
                            .min(Comparator.comparingInt(this::tabTimestamp))
                            .orElse(null);
            if (oldest != null) {
                removeTabFields(oldest);
            }
        }

        String tabId = Base64Url.encode(SecretGenerator.getInstance().randomBytes(8));
        int now = Time.currentTime();
        set(tabField(tabId, "exists"), "1");
        set(tabField(tabId, "clientUUID"), client.getId());
        set(tabField(tabId, "timestamp"), Integer.toString(now));
        setTimestamp(now);

        RedisAuthenticationSessionAdapter authSession = new RedisAuthenticationSessionAdapter(session, this, tabId);
        session.getContext().setAuthenticationSession(authSession);
        return authSession;
    }

    @Override
    public void removeAuthenticationSessionByTabId(String tabId) {
        if (!hasTab(tabId)) {
            return;
        }
        removeTabFields(tabId);
        if (tabIds().isEmpty()) {
            provider.removeRootAuthenticationSession(realm, this);
        } else {
            setTimestamp(Time.currentTime());
        }
    }

    @Override
    public void restartSession(RealmModel realm) {
        for (String tabId : Set.copyOf(tabIds())) {
            removeTabFields(tabId);
        }
        set(REALM_ID, realm.getId());
        setTimestamp(Time.currentTime());
    }

    void updateExpiration() {
        int lifespan = realm.getAccessCodeLifespanLogin();
        if (lifespan <= 0) {
            lifespan = realm.getAccessCodeLifespanUserAction();
        }
        if (lifespan <= 0) {
            lifespan = 1800;
        }
        setExpiration(getTimestamp() * 1000L + lifespan * 1000L);
    }

    boolean hasTab(String tabId) {
        return "1".equals(get(tabField(tabId, "exists")));
    }

    Set<String> tabIds() {
        Set<String> tabs = new LinkedHashSet<>();
        String existsSuffix = TAB_EXISTS_SUFFIX;
        for (String field : snapshot().keySet()) {
            if (field.startsWith(TAB_PREFIX) && field.endsWith(existsSuffix)) {
                tabs.add(field.substring(TAB_PREFIX.length(), field.length() - existsSuffix.length()));
            }
        }
        return tabs;
    }

    String getTabField(String tabId, String field) {
        return get(tabField(tabId, field));
    }

    void setTabField(String tabId, String field, String value) {
        if (value == null) {
            remove(tabField(tabId, field));
        } else {
            set(tabField(tabId, field), value);
        }
    }

    void putTabMap(String tabId, String mapPrefix, String name, String value) {
        if (name == null) {
            return;
        }
        if (value == null) {
            remove(tabField(tabId, mapPrefix + name));
        } else {
            set(tabField(tabId, mapPrefix + name), value);
        }
    }

    Map<String, String> getTabMap(String tabId, String mapPrefix) {
        String fullPrefix = TAB_PREFIX + tabId + "." + mapPrefix;
        return getMap(fullPrefix);
    }

    void clearTabMap(String tabId, String mapPrefix) {
        for (String name : Map.copyOf(getTabMap(tabId, mapPrefix)).keySet()) {
            remove(tabField(tabId, mapPrefix + name));
        }
    }

    private int tabTimestamp(String tabId) {
        Integer ts = TimeAdapter.parseInt(getTabField(tabId, "timestamp"));
        return ts == null ? 0 : ts;
    }

    private void removeTabFields(String tabId) {
        String prefix = TAB_PREFIX + tabId + ".";
        for (String field : Map.copyOf(snapshot()).keySet()) {
            if (field.startsWith(prefix)) {
                remove(field);
            }
        }
    }

    static String tabField(String tabId, String field) {
        return TAB_PREFIX + tabId + "." + field;
    }

    public static RedisRootAuthenticationSessionAdapter createNew(
            KeycloakSession session,
            RedisAuthenticationSessionProvider provider,
            RootAuthenticationSessionKey key,
            RealmModel realm,
            int authSessionsLimit) {
        MapEntity entity = MapEntity.createNew();
        entity.set(ID, key.id());
        entity.set(REALM_ID, realm.getId());
        entity.set(TIMESTAMP, Integer.toString(Time.currentTime()));
        RedisRootAuthenticationSessionAdapter adapter =
                new RedisRootAuthenticationSessionAdapter(
                        session, provider, key, realm, entity, authSessionsLimit);
        adapter.updateExpiration();
        return adapter;
    }
}
