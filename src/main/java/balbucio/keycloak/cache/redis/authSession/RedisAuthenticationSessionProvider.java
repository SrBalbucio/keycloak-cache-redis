package balbucio.keycloak.cache.redis.authSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import balbucio.keycloak.cache.redis.RedisChangelogTransaction;
import balbucio.keycloak.cache.redis.connection.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;

public class RedisAuthenticationSessionProvider implements AuthenticationSessionProvider {

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

    @Override
    public void close() {}

    private Collection<RedisChangelogTransaction.IndexUpdate> indexes(
            RedisRootAuthenticationSessionAdapter adapter) {
        List<RedisChangelogTransaction.IndexUpdate> list = new ArrayList<>();
        String realmId = adapter.get(RedisRootAuthenticationSessionAdapter.REALM_ID);
        if (realmId != null) {
            list.add(
                    new RedisChangelogTransaction.IndexUpdate(
                            AuthSessionIndexes.realmIndex(realmId), adapter.getId()));
        }
        return list;
    }
}
