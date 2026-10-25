package balbucio.keycloak.cache.redis.authSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import balbucio.keycloak.cache.redis.RedisChangelogTransaction;
import balbucio.keycloak.cache.redis.RedisMetrics;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import io.lettuce.core.ScriptOutputType;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;

public class RedisAuthenticationSessionProvider implements AuthenticationSessionProvider {

    /**
     * Prunes expired members (score = expiration ms) and returns the live ones in one round-trip.
     * Redis ZSET members don't expire with their hashes, so the score-based prune is the lazy TTL
     * cleanup for the by-user index.
     */
    private static final String PRUNE_AND_READ_INDEX_SCRIPT =
            """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            return redis.call('ZRANGE', KEYS[1], 0, -1)
            """;

    private final KeycloakSession session;
    private final int authSessionsLimit;
    private final RedisConnectionProvider connection;
    private final RedisChangelogTransaction<RootAuthenticationSessionKey, RedisRootAuthenticationSessionAdapter>
            roots;

    public RedisAuthenticationSessionProvider(KeycloakSession session, int authSessionsLimit) {
        this.session = session;
        this.authSessionsLimit = authSessionsLimit;
        this.connection = session.getProvider(RedisConnectionProvider.class);
        this.roots =
                new RedisChangelogTransaction<>(
                        session,
                        connection,
                        (key, entity) -> {
                            String realmId = entity.get(RedisRootAuthenticationSessionAdapter.REALM_ID);
                            RealmModel realm = realmId == null ? null : session.realms().getRealm(realmId);
                            return new RedisRootAuthenticationSessionAdapter(
                                    session, this, key, realm, entity, authSessionsLimit);
                        },
                        this::indexes);
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm) {
        return createRootAuthenticationSession(realm, KeycloakModelUtils.generateId());
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm, String id) {
        if (id == null) {
            id = KeycloakModelUtils.generateId();
        }
        RootAuthenticationSessionKey key = RootAuthenticationSessionKey.of(realm.getId(), id);
        RedisRootAuthenticationSessionAdapter adapter =
                RedisRootAuthenticationSessionAdapter.createNew(
                        session, this, key, realm, authSessionsLimit);
        roots.create(key, adapter);
        return adapter;
    }

    @Override
    public RootAuthenticationSessionModel getRootAuthenticationSession(
            RealmModel realm, String authenticationSessionId) {
        if (authenticationSessionId == null || realm == null) {
            return null;
        }
        RedisRootAuthenticationSessionAdapter adapter =
                roots.get(RootAuthenticationSessionKey.of(realm.getId(), authenticationSessionId));
        if (adapter == null) {
            return null;
        }
        if (!Objects.equals(realm.getId(), adapter.get(RedisRootAuthenticationSessionAdapter.REALM_ID))) {
            return null;
        }
        return adapter;
    }

    @Override
    public void removeRootAuthenticationSession(
            RealmModel realm, RootAuthenticationSessionModel authenticationSession) {
        Objects.requireNonNull(realm);
        Objects.requireNonNull(authenticationSession);
        if (!Objects.equals(realm.getId(), authenticationSession.getRealm().getId())) {
            throw new ModelException(
                    "Authentication session with id '"
                            + authenticationSession.getId()
                            + "' does not belong to realm '"
                            + realm.getId()
                            + "'");
        }
        roots.delete(RootAuthenticationSessionKey.of(realm.getId(), authenticationSession.getId()));
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        Set<String> ids = connection.sync().smembers(AuthSessionIndexes.realmIndex(realm.getId()));
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            roots.delete(RootAuthenticationSessionKey.of(realm.getId(), id));
        }
    }

    /**
     * KC 26.8 "sign out other devices": after a password change (or explicit sign-out of other
     * devices) the root authentication sessions of the user's in-progress logins must end too.
     * The stock default is a no-op, which silently kept those logins alive; the by-user index
     * makes them findable. Stale members (index written, then the tab's user changed) are
     * re-verified against the live tabs before deletion, like the reference Cassandra extension.
     */
    @Override
    public void removeRootAuthenticationSessionsByAuthenticatedUser(
            RealmModel realm, UserModel user, String rootAuthenticationSessionIdToKeep) {
        Objects.requireNonNull(realm);
        Objects.requireNonNull(user);
        String indexKey = AuthSessionIndexes.userIndex(realm.getId(), user.getId());
        List<String> members =
                readAndPruneIndex(indexKey);
        if (members.isEmpty()) {
            return;
        }
        for (String rootId : members) {
            if (rootId.equals(rootAuthenticationSessionIdToKeep)) {
                continue;
            }
            RedisRootAuthenticationSessionAdapter root =
                    roots.get(RootAuthenticationSessionKey.of(realm.getId(), rootId));
            if (root == null) {
                // Hash expired/purged after the index member was written; drop the stale member.
                connection.sync().zrem(indexKey, rootId);
                continue;
            }
            boolean authenticatedAsUser = root.tabIds().stream()
                    .anyMatch(tabId -> user.getId()
                            .equals(root.getTabField(tabId, RedisRootAuthenticationSessionAdapter.TAB_AUTH_USER)));
            if (!authenticatedAsUser) {
                // Index member predates a setAuthenticatedUser change on the tab.
                connection.sync().zrem(indexKey, rootId);
                continue;
            }
            removeRootAuthenticationSession(realm, root);
        }
    }

    @Override
    public void close() {}

    @SuppressWarnings("unchecked")
    private List<String> readAndPruneIndex(String indexKey) {
        Object raw = connection
                .sync()
                .eval(
                        PRUNE_AND_READ_INDEX_SCRIPT,
                        ScriptOutputType.MULTI,
                        new String[] {indexKey},
                        Long.toString(Time.currentTimeMillis()));
        RedisMetrics.record(RedisMetrics.Cache.GENERIC, RedisMetrics.Op.EVAL);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return (List<String>) list;
    }

    private Collection<RedisChangelogTransaction.IndexUpdate> indexes(
            RedisRootAuthenticationSessionAdapter adapter) {
        List<RedisChangelogTransaction.IndexUpdate> list = new ArrayList<>();
        String realmId = adapter.get(RedisRootAuthenticationSessionAdapter.REALM_ID);
        if (realmId != null) {
            list.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            AuthSessionIndexes.realmIndex(realmId), adapter.getId()));
            // By-user ZSET: member = root session id, score = expiration (ms) so reads prune
            // expired members. Every tab authenticated as a user contributes one entry.
            Double score = expirationScore(adapter);
            for (String tabId : adapter.tabIds()) {
                String userId = adapter.getTabField(tabId, RedisRootAuthenticationSessionAdapter.TAB_AUTH_USER);
                if (userId != null && score != null) {
                    list.add(RedisChangelogTransaction.IndexUpdate.zset(
                            AuthSessionIndexes.userIndex(realmId, userId), adapter.getId(), score));
                }
            }
        }
        return list;
    }

    private static Double expirationScore(RedisRootAuthenticationSessionAdapter adapter) {
        Long expiration = adapter.getExpiration();
        if (expiration != null) {
            return expiration.doubleValue();
        }
        // Defensive fallback: root sessions always carry an expiration, but a missing one must
        // not make the member immortal in the index.
        return (double) (Time.currentTimeMillis() + 30L * 60L * 1000L);
    }
}
