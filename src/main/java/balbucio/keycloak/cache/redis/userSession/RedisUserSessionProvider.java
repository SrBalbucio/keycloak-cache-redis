package balbucio.keycloak.cache.redis.userSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import balbucio.keycloak.cache.redis.RedisChangelogTransaction;
import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

public class RedisUserSessionProvider implements UserSessionProvider {

    private final KeycloakSession session;
    private final int startupTime;
    private final RedisConnectionProvider connection;
    private final RedisChangelogTransaction<UserSessionKey, RedisUserSessionAdapter> userSessions;
    private final RedisChangelogTransaction<AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
            clientSessions;

    public RedisUserSessionProvider(KeycloakSession session, int startupTime) {
        this.session = session;
        this.startupTime = startupTime;
        this.connection = session.getProvider(RedisConnectionProvider.class);
        this.userSessions =
                new RedisChangelogTransaction<>(
                        session,
                        connection,
                        (key, entity) -> new RedisUserSessionAdapter(session, this, key, entity),
                        this::userSessionIndexes);
        this.clientSessions =
                new RedisChangelogTransaction<>(
                        session,
                        connection,
                        (key, entity) ->
                                new RedisAuthenticatedClientSessionAdapter(session, this, key, entity, null),
                        this::clientSessionIndexes);
    }

    @Override
    public KeycloakSession getKeycloakSession() {
        return session;
    }

    @Override
    public AuthenticatedClientSessionModel createClientSession(
            RealmModel realm, ClientModel client, UserSessionModel userSession) {
        AuthenticatedClientSessionKey key =
                AuthenticatedClientSessionKey.of(userSession.getId(), client.getId(), userSession.isOffline());
        RedisAuthenticatedClientSessionAdapter adapter =
                RedisAuthenticatedClientSessionAdapter.createNew(session, this, key, realm, client, userSession);
        clientSessions.create(key, adapter);
        if (userSession instanceof RedisUserSessionAdapter redisUserSession) {
            redisUserSession.addClientSession(client.getId());
        }
        return adapter;
    }

    @Override
    public AuthenticatedClientSessionModel getClientSession(
            UserSessionModel userSession, ClientModel client, boolean offline) {
        if (userSession == null || client == null) {
            return null;
        }
        AuthenticatedClientSessionKey key =
                AuthenticatedClientSessionKey.of(userSession.getId(), client.getId(), offline);
        RedisAuthenticatedClientSessionAdapter adapter = clientSessions.get(key);
        if (adapter != null) {
            return adapter;
        }
        return null;
    }

    @Override
    public UserSessionModel createUserSession(
            String id,
            RealmModel realm,
            UserModel user,
            String loginUsername,
            String ipAddress,
            String authMethod,
            boolean rememberMe,
            String brokerSessionId,
            String brokerUserId,
            UserSessionModel.SessionPersistenceState persistenceState) {

        if (id == null) {
            id = KeycloakModelUtils.generateId();
        }
        UserSessionKey key = UserSessionKey.of(id, false);
        RedisUserSessionAdapter adapter =
                RedisUserSessionAdapter.createNew(
                        session,
                        this,
                        key,
                        realm,
                        user,
                        loginUsername,
                        ipAddress,
                        authMethod,
                        rememberMe,
                        brokerSessionId,
                        brokerUserId,
                        persistenceState == null
                                ? UserSessionModel.SessionPersistenceState.PERSISTENT
                                : persistenceState);

        if (adapter.getPersistenceState() == UserSessionModel.SessionPersistenceState.TRANSIENT) {
            // Not enlisted — request-scoped only, never written to Redis
            return adapter;
        }

        userSessions.create(key, adapter);
        return adapter;
    }

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
        RedisUserSessionAdapter adapter = getUserSessionById(id, false);
        if (adapter == null) {
            return null;
        }
        if (realm != null && !Objects.equals(realm.getId(), adapter.get(RedisUserSessionAdapter.REALM_ID))) {
            return null;
        }
        return adapter;
    }

    RedisUserSessionAdapter getUserSessionById(String id, boolean offline) {
        if (id == null) {
            return null;
        }
        return userSessions.get(UserSessionKey.of(id, offline));
    }

    void removeClientSession(String userSessionId, String clientId, boolean offline) {
        clientSessions.delete(AuthenticatedClientSessionKey.of(userSessionId, clientId, offline));
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
        return streamFromIndex(UserSessionIndexes.userIndex(user.getId(), false), realm, false);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client) {
        return getUserSessionsStream(realm, client, null, null);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(
            RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        Stream<UserSessionModel> stream =
                streamFromClientIndex(UserSessionIndexes.clientIndex(client.getId(), false), realm, false);
        if (firstResult != null) {
            stream = stream.skip(firstResult);
        }
        if (maxResults != null) {
            stream = stream.limit(maxResults);
        }
        return stream;
    }

    @Override
    public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return streamFromIndex(UserSessionIndexes.brokerUserIndex(brokerUserId, false), realm, false);
    }

    @Override
    public UserSessionModel getUserSessionByBrokerSessionId(RealmModel realm, String brokerSessionId) {
        return streamFromIndex(UserSessionIndexes.brokerSessionIndex(brokerSessionId, false), realm, false)
                .findFirst()
                .orElse(null);
    }

    @Override
    public UserSessionModel getUserSessionWithPredicate(
            RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
        UserSessionModel userSession = offline ? getOfflineUserSession(realm, id) : getUserSession(realm, id);
        if (userSession == null) {
            return null;
        }
        return predicate.test(userSession) ? userSession : null;
    }

    @Override
    public long getActiveUserSessions(RealmModel realm, ClientModel client) {
        return getUserSessionsStream(realm, client).count();
    }

    @Override
    public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
        Map<String, Long> stats = new HashMap<>();
        Set<String> ids = connection.sync().smembers(UserSessionIndexes.realmIndex(realm.getId(), offline));
        if (ids == null) {
            return stats;
        }
        for (String id : ids) {
            RedisUserSessionAdapter sessionAdapter = getUserSessionById(id, offline);
            if (sessionAdapter == null) {
                continue;
            }
            for (String clientId : sessionAdapter.getAuthenticatedClientSessions().keySet()) {
                stats.merge(clientId, 1L, Long::sum);
            }
        }
        return stats;
    }

    @Override
    public void removeUserSession(RealmModel realm, UserSessionModel session) {
        if (session == null) {
            return;
        }
        removeUserSession(session.getId(), session.isOffline());
    }

    private void removeUserSession(String id, boolean offline) {
        RedisUserSessionAdapter adapter = getUserSessionById(id, offline);
        if (adapter != null) {
            for (String clientId : Map.copyOf(adapter.getMap(Constants.CLIENT_SESSION_PREFIX)).keySet()) {
                removeClientSession(id, clientId, offline);
            }
        }
        userSessions.delete(UserSessionKey.of(id, offline));
    }

    @Override
    public void removeUserSessions(RealmModel realm, UserModel user) {
        List<UserSessionModel> sessions = getUserSessionsStream(realm, user).toList();
        for (UserSessionModel s : sessions) {
            removeUserSession(realm, s);
        }
        List<UserSessionModel> offlineSessions = getOfflineUserSessionsStream(realm, user).toList();
        for (UserSessionModel s : offlineSessions) {
            removeOfflineUserSession(realm, s);
        }
    }

    @Override
    public void removeAllExpired() {
        // Redis TTL handles expiration; lazy checks on read
    }

    @Override
    public void removeExpired(RealmModel realm) {
        // Redis TTL handles expiration; lazy checks on read
    }

    @Override
    public void removeUserSessions(RealmModel realm) {
        Set<String> ids = connection.sync().smembers(UserSessionIndexes.realmIndex(realm.getId(), false));
        if (ids != null) {
            for (String id : ids) {
                removeUserSession(id, false);
            }
        }
        Set<String> offlineIds = connection.sync().smembers(UserSessionIndexes.realmIndex(realm.getId(), true));
        if (offlineIds != null) {
            for (String id : offlineIds) {
                removeUserSession(id, true);
            }
        }
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        removeUserSessions(realm);
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        List<UserSessionModel> sessions = getUserSessionsStream(realm, client).toList();
        for (UserSessionModel s : sessions) {
            s.removeAuthenticatedClientSessions(List.of(client.getId()));
        }
        List<UserSessionModel> offline =
                getOfflineUserSessionsStream(realm, client, null, null).toList();
        for (UserSessionModel s : offline) {
            s.removeAuthenticatedClientSessions(List.of(client.getId()));
        }
    }

    @Override
    public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
        UserSessionKey key = UserSessionKey.of(userSession.getId(), true);
        RedisUserSessionAdapter offline =
                RedisUserSessionAdapter.createNew(
                        session,
                        this,
                        key,
                        userSession.getRealm(),
                        userSession.getUser(),
                        userSession.getLoginUsername(),
                        userSession.getIpAddress(),
                        userSession.getAuthMethod(),
                        userSession.isRememberMe(),
                        userSession.getBrokerSessionId(),
                        userSession.getBrokerUserId(),
                        UserSessionModel.SessionPersistenceState.PERSISTENT);
        offline.setNote(UserSessionModel.CORRESPONDING_SESSION_ID, userSession.getId());
        offline.setLastSessionRefresh(Time.currentTime());
        userSessions.create(key, offline);

        // corresponding index on online session
        if (userSession instanceof RedisUserSessionAdapter online) {
            online.setNote(UserSessionModel.CORRESPONDING_SESSION_ID, offline.getId());
        }
        return offline;
    }

    @Override
    public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
        RedisUserSessionAdapter adapter = getUserSessionById(userSessionId, true);
        if (adapter == null) {
            return null;
        }
        if (realm != null && !Objects.equals(realm.getId(), adapter.get(RedisUserSessionAdapter.REALM_ID))) {
            return null;
        }
        return adapter;
    }

    @Override
    public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
        if (userSession == null) {
            return;
        }
        removeUserSession(userSession.getId(), true);
    }

    @Override
    public AuthenticatedClientSessionModel createOfflineClientSession(
            AuthenticatedClientSessionModel clientSession, UserSessionModel offlineUserSession) {
        ClientModel client = clientSession.getClient();
        AuthenticatedClientSessionKey key =
                AuthenticatedClientSessionKey.of(offlineUserSession.getId(), client.getId(), true);
        RedisAuthenticatedClientSessionAdapter adapter =
                RedisAuthenticatedClientSessionAdapter.createNew(
                        session, this, key, offlineUserSession.getRealm(), client, offlineUserSession);
        adapter.setRedirectUri(clientSession.getRedirectUri());
        adapter.setProtocol(clientSession.getProtocol());
        for (Map.Entry<String, String> note : clientSession.getNotes().entrySet()) {
            adapter.setNote(note.getKey(), note.getValue());
        }
        clientSessions.create(key, adapter);
        if (offlineUserSession instanceof RedisUserSessionAdapter redisUserSession) {
            redisUserSession.addClientSession(client.getId());
        }
        return adapter;
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
        return streamFromIndex(UserSessionIndexes.userIndex(user.getId(), true), realm, true);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(
            RealmModel realm, String brokerUserId) {
        return streamFromIndex(UserSessionIndexes.brokerUserIndex(brokerUserId, true), realm, true);
    }

    @Override
    public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
        return getOfflineUserSessionsStream(realm, client, null, null).count();
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(
            RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        Stream<UserSessionModel> stream =
                streamFromClientIndex(UserSessionIndexes.clientIndex(client.getId(), true), realm, true);
        if (firstResult != null) {
            stream = stream.skip(firstResult);
        }
        if (maxResults != null) {
            stream = stream.limit(maxResults);
        }
        return stream;
    }

    @Override
    public void importUserSessions(Collection<UserSessionModel> persistentUserSessions, boolean offline) {
        // MVP: no migration of existing sessions
    }

    @Override
    public void close() {}

    @Override
    public int getStartupTime(RealmModel realm) {
        return startupTime;
    }

    private Stream<UserSessionModel> streamFromIndex(String indexKey, RealmModel realm, boolean offline) {
        Set<String> ids = connection.sync().smembers(indexKey);
        if (ids == null || ids.isEmpty()) {
            return Stream.empty();
        }
        List<UserSessionKey> keys = ids.stream().map(id -> UserSessionKey.of(id, offline)).toList();
        Map<UserSessionKey, RedisUserSessionAdapter> loaded = userSessions.getAll(keys);
        return loaded.values().stream()
                .filter(s -> realm == null || Objects.equals(realm.getId(), s.get(RedisUserSessionAdapter.REALM_ID)))
                .map(UserSessionModel.class::cast);
    }

    private Stream<UserSessionModel> streamFromClientIndex(String indexKey, RealmModel realm, boolean offline) {
        Set<String> compoundIds = connection.sync().smembers(indexKey);
        if (compoundIds == null || compoundIds.isEmpty()) {
            return Stream.empty();
        }
        List<UserSessionModel> sessions = new ArrayList<>();
        for (String compound : compoundIds) {
            int sep = compound.indexOf("::");
            if (sep <= 0) {
                continue;
            }
            String userSessionId = compound.substring(0, sep);
            RedisUserSessionAdapter adapter = getUserSessionById(userSessionId, offline);
            if (adapter != null
                    && (realm == null
                            || Objects.equals(realm.getId(), adapter.get(RedisUserSessionAdapter.REALM_ID)))) {
                sessions.add(adapter);
            }
        }
        return sessions.stream().distinct();
    }

    private Collection<RedisChangelogTransaction.IndexUpdate> userSessionIndexes(RedisUserSessionAdapter adapter) {
        List<RedisChangelogTransaction.IndexUpdate> indexes = new ArrayList<>();
        boolean offline = adapter.isOffline();
        String id = adapter.getId();
        String realmId = adapter.get(RedisUserSessionAdapter.REALM_ID);
        String userId = adapter.get(RedisUserSessionAdapter.USER_ID);
        if (realmId != null) {
            indexes.add(new RedisChangelogTransaction.IndexUpdate(UserSessionIndexes.realmIndex(realmId, offline), id));
        }
        if (userId != null) {
            indexes.add(new RedisChangelogTransaction.IndexUpdate(UserSessionIndexes.userIndex(userId, offline), id));
        }
        String brokerSessionId = adapter.get(RedisUserSessionAdapter.BROKER_SESSION_ID);
        if (brokerSessionId != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.brokerSessionIndex(brokerSessionId, offline), id));
        }
        String brokerUserId = adapter.get(RedisUserSessionAdapter.BROKER_USER_ID);
        if (brokerUserId != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.brokerUserIndex(brokerUserId, offline), id));
        }
        String corresponding = adapter.getNote(UserSessionModel.CORRESPONDING_SESSION_ID);
        if (corresponding != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.correspondingSessionIndex(corresponding, offline), id));
        }
        return indexes;
    }

    private Collection<RedisChangelogTransaction.IndexUpdate> clientSessionIndexes(
            RedisAuthenticatedClientSessionAdapter adapter) {
        List<RedisChangelogTransaction.IndexUpdate> indexes = new ArrayList<>();
        AuthenticatedClientSessionKey key = adapter.getKey();
        indexes.add(
                new RedisChangelogTransaction.IndexUpdate(
                        UserSessionIndexes.clientIndex(key.clientId(), key.offline()), key.compoundId()));
        return indexes;
    }
}
