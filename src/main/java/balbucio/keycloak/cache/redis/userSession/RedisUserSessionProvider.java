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
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.utils.KeycloakModelUtils;

public class RedisUserSessionProvider implements UserSessionProvider {

    private static final Logger LOG = Logger.getLogger(RedisUserSessionProvider.class);

    private final KeycloakSession session;
    private final int startupTime;
    private final boolean persistOfflineSessions;
    private final RedisConnectionProvider connection;
    private final RedisChangelogTransaction<UserSessionKey, RedisUserSessionAdapter> userSessions;
    private final RedisChangelogTransaction<AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
            clientSessions;

    public RedisUserSessionProvider(KeycloakSession session, int startupTime, boolean persistOfflineSessions) {
        this.session = session;
        this.startupTime = startupTime;
        this.persistOfflineSessions = persistOfflineSessions;
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
                AuthenticatedClientSessionKey.of(
                        realm.getId(), userSession.getId(), client.getId(), userSession.isOffline());
        RedisAuthenticatedClientSessionAdapter adapter =
                RedisAuthenticatedClientSessionAdapter.createNew(session, this, key, realm, client, userSession);
        clientSessions.create(key, adapter);
        if (userSession instanceof RedisUserSessionAdapter redisUserSession) {
            redisUserSession.addClientSession(client.getId());
        }
        adjustClientStats(realm.getId(), client.getId(), userSession.isOffline(), 1);
        return adapter;
    }

    @Override
    public AuthenticatedClientSessionModel getClientSession(
            UserSessionModel userSession, ClientModel client, boolean offline) {
        if (userSession == null || client == null) {
            return null;
        }
        String realmId = userSession.getRealm() != null ? userSession.getRealm().getId() : null;
        if (realmId == null && userSession instanceof RedisUserSessionAdapter redis) {
            realmId = redis.get(RedisUserSessionAdapter.REALM_ID);
        }
        if (realmId == null) {
            return null;
        }
        AuthenticatedClientSessionKey key =
                AuthenticatedClientSessionKey.of(realmId, userSession.getId(), client.getId(), offline);
        return clientSessions.get(key);
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
        UserSessionKey key = UserSessionKey.of(realm.getId(), id, false);
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
            return adapter;
        }

        userSessions.create(key, adapter);
        return adapter;
    }

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
        if (realm == null || id == null) {
            return null;
        }
        return getUserSessionById(realm.getId(), id, false);
    }

    RedisUserSessionAdapter getUserSessionById(String realmId, String id, boolean offline) {
        if (realmId == null || id == null) {
            return null;
        }
        return userSessions.get(UserSessionKey.of(realmId, id, offline));
    }

    void removeClientSession(String realmId, String userSessionId, String clientId, boolean offline) {
        clientSessions.delete(AuthenticatedClientSessionKey.of(realmId, userSessionId, clientId, offline));
        adjustClientStats(realmId, clientId, offline, -1);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
        return streamFromIndex(
                UserSessionIndexes.userIndex(realm.getId(), user.getId(), false), realm, false);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client) {
        return getUserSessionsStream(realm, client, null, null);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(
            RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        return streamFromClientZIndex(realm, client.getId(), false, firstResult, maxResults);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return streamFromIndex(
                UserSessionIndexes.brokerUserIndex(realm.getId(), brokerUserId, false), realm, false);
    }

    @Override
    public UserSessionModel getUserSessionByBrokerSessionId(RealmModel realm, String brokerSessionId) {
        return streamFromIndex(
                        UserSessionIndexes.brokerSessionIndex(realm.getId(), brokerSessionId, false),
                        realm,
                        false)
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
        Long n = connection.sync().zcard(UserSessionIndexes.clientZIndex(realm.getId(), client.getId(), false));
        return n == null ? 0L : n;
    }

    @Override
    public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
        Map<String, Long> stats = new HashMap<>();
        Set<String> clientIds =
                connection.sync().smembers(UserSessionIndexes.clientStatsIndex(realm.getId(), offline));
        if (clientIds == null || clientIds.isEmpty()) {
            return stats;
        }
        List<String> keys = new ArrayList<>(clientIds.size());
        List<String> ordered = new ArrayList<>(clientIds.size());
        for (String clientId : clientIds) {
            ordered.add(clientId);
            keys.add(UserSessionIndexes.clientStats(realm.getId(), clientId, offline));
        }
        List<String> values = connection.sync().mget(keys.toArray(new String[0]));
        for (int i = 0; i < ordered.size(); i++) {
            String raw = values != null && i < values.size() ? values.get(i) : null;
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                long n = Long.parseLong(raw.trim());
                if (n > 0) {
                    stats.put(ordered.get(i), n);
                }
            } catch (NumberFormatException ignored) {
                // skip bad counter
            }
        }
        return stats;
    }

    @Override
    public void removeUserSession(RealmModel realm, UserSessionModel session) {
        if (session == null) {
            return;
        }
        String realmId = realm != null ? realm.getId() : session.getRealm().getId();
        removeUserSession(realmId, session.getId(), session.isOffline());
    }

    private void removeUserSession(String realmId, String id, boolean offline) {
        RedisUserSessionAdapter adapter = getUserSessionById(realmId, id, offline);
        if (adapter != null) {
            for (String clientId : Map.copyOf(adapter.getMap(Constants.CLIENT_SESSION_PREFIX)).keySet()) {
                removeClientSession(realmId, id, clientId, offline);
            }
        }
        userSessions.delete(UserSessionKey.of(realmId, id, offline));
        if (offline && persistOfflineSessions) {
            persistRemoveUserSession(id, true);
        }
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
        removeAllFromRealmIndex(realm.getId(), false);
        removeAllFromRealmIndex(realm.getId(), true);
    }

    private void removeAllFromRealmIndex(String realmId, boolean offline) {
        Set<String> ids = connection.sync().smembers(UserSessionIndexes.realmIndex(realmId, offline));
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<UserSessionKey> keys =
                ids.stream().map(id -> UserSessionKey.of(realmId, id, offline)).toList();
        Map<UserSessionKey, RedisUserSessionAdapter> loaded = userSessions.getAll(keys);
        for (String id : ids) {
            RedisUserSessionAdapter adapter = loaded.get(UserSessionKey.of(realmId, id, offline));
            if (adapter != null) {
                for (String clientId : Map.copyOf(adapter.getMap(Constants.CLIENT_SESSION_PREFIX)).keySet()) {
                    removeClientSession(realmId, id, clientId, offline);
                }
            }
            userSessions.delete(UserSessionKey.of(realmId, id, offline));
        }
        Set<String> statClients =
                connection.sync().smembers(UserSessionIndexes.clientStatsIndex(realmId, offline));
        if (statClients != null) {
            for (String clientId : statClients) {
                connection.sync().del(UserSessionIndexes.clientStats(realmId, clientId, offline));
            }
        }
        connection.sync().del(UserSessionIndexes.clientStatsIndex(realmId, offline));
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        removeUserSessions(realm);
        if (persistOfflineSessions) {
            UserSessionPersisterProvider persister = session.getProvider(UserSessionPersisterProvider.class);
            if (persister != null) {
                persister.onRealmRemoved(realm);
            }
        }
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
        connection.sync().del(UserSessionIndexes.clientStats(realm.getId(), client.getId(), false));
        connection.sync().del(UserSessionIndexes.clientStats(realm.getId(), client.getId(), true));
        connection.sync().srem(UserSessionIndexes.clientStatsIndex(realm.getId(), false), client.getId());
        connection.sync().srem(UserSessionIndexes.clientStatsIndex(realm.getId(), true), client.getId());
    }

    @Override
    public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
        RealmModel realm = userSession.getRealm();
        UserSessionKey key = UserSessionKey.of(realm.getId(), userSession.getId(), true);
        RedisUserSessionAdapter offline =
                RedisUserSessionAdapter.createNew(
                        session,
                        this,
                        key,
                        realm,
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

        if (userSession instanceof RedisUserSessionAdapter online) {
            online.setNote(UserSessionModel.CORRESPONDING_SESSION_ID, offline.getId());
        }
        if (persistOfflineSessions) {
            persistCreateUserSession(offline, true);
        }
        return offline;
    }

    @Override
    public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
        if (realm == null || userSessionId == null) {
            return null;
        }
        return getUserSessionById(realm.getId(), userSessionId, true);
    }

    @Override
    public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
        if (userSession == null) {
            return;
        }
        String realmId = realm != null ? realm.getId() : userSession.getRealm().getId();
        removeUserSession(realmId, userSession.getId(), true);
    }

    @Override
    public AuthenticatedClientSessionModel createOfflineClientSession(
            AuthenticatedClientSessionModel clientSession, UserSessionModel offlineUserSession) {
        ClientModel client = clientSession.getClient();
        RealmModel realm = offlineUserSession.getRealm();
        AuthenticatedClientSessionKey key =
                AuthenticatedClientSessionKey.of(
                        realm.getId(), offlineUserSession.getId(), client.getId(), true);
        RedisAuthenticatedClientSessionAdapter adapter =
                RedisAuthenticatedClientSessionAdapter.createNew(
                        session, this, key, realm, client, offlineUserSession);
        adapter.setRedirectUri(clientSession.getRedirectUri());
        adapter.setProtocol(clientSession.getProtocol());
        for (Map.Entry<String, String> note : clientSession.getNotes().entrySet()) {
            adapter.setNote(note.getKey(), note.getValue());
        }
        clientSessions.create(key, adapter);
        if (offlineUserSession instanceof RedisUserSessionAdapter redisUserSession) {
            redisUserSession.addClientSession(client.getId());
        }
        adjustClientStats(realm.getId(), client.getId(), true, 1);
        if (persistOfflineSessions) {
            persistCreateClientSession(adapter, true);
        }
        return adapter;
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
        return streamFromIndex(
                UserSessionIndexes.userIndex(realm.getId(), user.getId(), true), realm, true);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(
            RealmModel realm, String brokerUserId) {
        return streamFromIndex(
                UserSessionIndexes.brokerUserIndex(realm.getId(), brokerUserId, true), realm, true);
    }

    @Override
    public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
        Long n = connection.sync().zcard(UserSessionIndexes.clientZIndex(realm.getId(), client.getId(), true));
        return n == null ? 0L : n;
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(
            RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        return streamFromClientZIndex(realm, client.getId(), true, firstResult, maxResults);
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

    boolean isPersistOfflineSessions() {
        return persistOfflineSessions;
    }

    private Stream<UserSessionModel> streamFromIndex(String indexKey, RealmModel realm, boolean offline) {
        Set<String> ids = connection.sync().smembers(indexKey);
        if (ids == null || ids.isEmpty()) {
            return Stream.empty();
        }
        String realmId = realm.getId();
        List<UserSessionKey> keys =
                ids.stream().map(id -> UserSessionKey.of(realmId, id, offline)).toList();
        Map<UserSessionKey, RedisUserSessionAdapter> loaded = userSessions.getAll(keys);
        return loaded.values().stream().map(UserSessionModel.class::cast);
    }

    private Stream<UserSessionModel> streamFromClientZIndex(
            RealmModel realm, String clientId, boolean offline, Integer firstResult, Integer maxResults) {
        long start = firstResult == null ? 0L : Math.max(0L, firstResult.longValue());
        long stop;
        if (maxResults == null) {
            stop = -1L;
        } else if (maxResults <= 0) {
            return Stream.empty();
        } else {
            stop = start + maxResults - 1;
        }
        List<String> ids =
                connection
                        .sync()
                        .zrevrange(
                                UserSessionIndexes.clientZIndex(realm.getId(), clientId, offline),
                                start,
                                stop);
        if (ids == null || ids.isEmpty()) {
            return Stream.empty();
        }
        String realmId = realm.getId();
        List<UserSessionKey> keys =
                ids.stream().map(id -> UserSessionKey.of(realmId, id, offline)).toList();
        Map<UserSessionKey, RedisUserSessionAdapter> loaded = userSessions.getAll(keys);
        // Preserve ZSET order
        return ids.stream()
                .map(id -> loaded.get(UserSessionKey.of(realmId, id, offline)))
                .filter(Objects::nonNull)
                .map(UserSessionModel.class::cast);
    }

    private Collection<RedisChangelogTransaction.IndexUpdate> userSessionIndexes(RedisUserSessionAdapter adapter) {
        List<RedisChangelogTransaction.IndexUpdate> indexes = new ArrayList<>();
        boolean offline = adapter.isOffline();
        String id = adapter.getId();
        String realmId = adapter.get(RedisUserSessionAdapter.REALM_ID);
        String userId = adapter.get(RedisUserSessionAdapter.USER_ID);
        if (realmId == null) {
            return indexes;
        }
        indexes.add(new RedisChangelogTransaction.IndexUpdate(UserSessionIndexes.realmIndex(realmId, offline), id));
        double refreshScore = adapter.getLastSessionRefresh();
        indexes.add(
                RedisChangelogTransaction.IndexUpdate.zset(
                        UserSessionIndexes.realmZIndex(realmId, offline), id, refreshScore));
        if (userId != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.userIndex(realmId, userId, offline), id));
        }
        String brokerSessionId = adapter.get(RedisUserSessionAdapter.BROKER_SESSION_ID);
        if (brokerSessionId != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.brokerSessionIndex(realmId, brokerSessionId, offline), id));
        }
        String brokerUserId = adapter.get(RedisUserSessionAdapter.BROKER_USER_ID);
        if (brokerUserId != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.brokerUserIndex(realmId, brokerUserId, offline), id));
        }
        String corresponding = adapter.getNote(UserSessionModel.CORRESPONDING_SESSION_ID);
        if (corresponding != null) {
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.correspondingSessionIndex(realmId, corresponding, offline),
                            id));
        }
        for (String clientId : adapter.getMap(Constants.CLIENT_SESSION_PREFIX).keySet()) {
            indexes.add(
                    RedisChangelogTransaction.IndexUpdate.zset(
                            UserSessionIndexes.clientZIndex(realmId, clientId, offline), id, refreshScore));
            indexes.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            UserSessionIndexes.clientIndex(realmId, clientId, offline), id));
        }
        return indexes;
    }

    private Collection<RedisChangelogTransaction.IndexUpdate> clientSessionIndexes(
            RedisAuthenticatedClientSessionAdapter adapter) {
        // Client-session entity indexes are maintained on the user-session side (client ZSET/SET).
        return List.of();
    }

    private void adjustClientStats(String realmId, String clientId, boolean offline, long delta) {
        if (realmId == null || clientId == null || delta == 0) {
            return;
        }
        try {
            String statsKey = UserSessionIndexes.clientStats(realmId, clientId, offline);
            Long value = connection.sync().incrby(statsKey, delta);
            String indexKey = UserSessionIndexes.clientStatsIndex(realmId, offline);
            if (value != null && value > 0) {
                connection.sync().sadd(indexKey, clientId);
            } else {
                connection.sync().srem(indexKey, clientId);
                if (value != null && value <= 0) {
                    connection.sync().del(statsKey);
                }
            }
        } catch (Exception e) {
            LOG.debugf(e, "Failed to adjust client session stats for %s/%s", realmId, clientId);
        }
    }

    private void persistCreateUserSession(UserSessionModel model, boolean offline) {
        try {
            UserSessionPersisterProvider persister = session.getProvider(UserSessionPersisterProvider.class);
            if (persister != null) {
                persister.createUserSession(model, offline);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to persist offline user session %s", model.getId());
        }
    }

    private void persistCreateClientSession(AuthenticatedClientSessionModel model, boolean offline) {
        try {
            UserSessionPersisterProvider persister = session.getProvider(UserSessionPersisterProvider.class);
            if (persister != null) {
                persister.createClientSession(model, offline);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to persist offline client session");
        }
    }

    private void persistRemoveUserSession(String id, boolean offline) {
        try {
            UserSessionPersisterProvider persister = session.getProvider(UserSessionPersisterProvider.class);
            if (persister != null) {
                persister.removeUserSession(id, offline);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove persisted offline user session %s", id);
        }
    }
}
