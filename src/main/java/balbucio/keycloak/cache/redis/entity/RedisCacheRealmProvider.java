package balbucio.keycloak.cache.redis.entity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientScopeProvider;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleProvider;
import org.keycloak.models.cache.CacheRealmProvider;

/**
 * CacheRealmProvider MVP: JPA delegates for realm/client/… with Redis indexes for
 * {@code getRealmByName} and {@code getClientByClientId}.
 */
public final class RedisCacheRealmProvider {

    private RedisCacheRealmProvider() {}

    public static CacheRealmProvider create(KeycloakSession session, RedisEntityIndexCache cache) {
        RealmProvider realms = session.getProvider(RealmProvider.class, "jpa");
        ClientProvider clients = session.getProvider(ClientProvider.class, "jpa");
        ClientScopeProvider clientScopes = session.getProvider(ClientScopeProvider.class, "jpa");
        GroupProvider groups = session.getProvider(GroupProvider.class, "jpa");
        RoleProvider roles = session.getProvider(RoleProvider.class, "jpa");

        InvocationHandler handler =
                new Handler(cache, realms, clients, clientScopes, groups, roles);

        return (CacheRealmProvider)
                Proxy.newProxyInstance(
                        CacheRealmProvider.class.getClassLoader(),
                        new Class<?>[] {CacheRealmProvider.class},
                        handler);
    }

    private static final class Handler implements InvocationHandler {
        private final RedisEntityIndexCache cache;
        private final RealmProvider realms;
        private final ClientProvider clients;
        private final ClientScopeProvider clientScopes;
        private final GroupProvider groups;
        private final RoleProvider roles;

        Handler(
                RedisEntityIndexCache cache,
                RealmProvider realms,
                ClientProvider clients,
                ClientScopeProvider clientScopes,
                GroupProvider groups,
                RoleProvider roles) {
            this.cache = cache;
            this.realms = realms;
            this.clients = clients;
            this.clientScopes = clientScopes;
            this.groups = groups;
            this.roles = roles;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if ("clear".equals(name)) {
                cache.clearAllAndBroadcast();
                return null;
            }
            if ("getRealmDelegate".equals(name)) {
                return realms;
            }
            if (name.startsWith("register") && name.endsWith("Invalidation")) {
                cache.clearAllAndBroadcast();
                return null;
            }
            if ("refreshMasterAdminRole".equals(name)) {
                return false;
            }

            if ("getRealmByName".equals(name) && args != null && args.length == 1 && args[0] instanceof String realmName) {
                String key = RedisEntityIndexCache.realmByName(realmName);
                String cachedId = cache.get(key);
                if (cachedId != null) {
                    RealmModel realm = realms.getRealm(cachedId);
                    if (realm != null) {
                        return realm;
                    }
                    cache.remove(key);
                }
                RealmModel realm = realms.getRealmByName(realmName);
                if (realm != null) {
                    cache.put(key, realm.getId());
                }
                return realm;
            }

            if ("getClientByClientId".equals(name)
                    && args != null
                    && args.length == 2
                    && args[0] instanceof RealmModel realm
                    && args[1] instanceof String clientId) {
                String key = RedisEntityIndexCache.clientByClientId(realm.getId(), clientId);
                String cachedId = cache.get(key);
                if (cachedId != null) {
                    ClientModel client = clients.getClientById(realm, cachedId);
                    if (client != null) {
                        return client;
                    }
                    cache.remove(key);
                }
                ClientModel client = clients.getClientByClientId(realm, clientId);
                if (client != null) {
                    cache.put(key, client.getId());
                }
                return client;
            }

            Object target = resolveTarget(method);
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause != null) {
                    throw cause;
                }
                throw e;
            }
        }

        private Object resolveTarget(Method method) {
            Class<?> decl = method.getDeclaringClass();
            if (decl == ClientProvider.class) {
                return clients;
            }
            if (decl == ClientScopeProvider.class) {
                return clientScopes;
            }
            if (decl == GroupProvider.class) {
                return groups;
            }
            if (decl == RoleProvider.class) {
                return roles;
            }
            if (decl == RealmProvider.class) {
                return realms;
            }
            if (decl == Object.class) {
                return this;
            }
            String n = method.getName();
            if (n.contains("ClientScope") || n.contains("clientScope")) {
                return clientScopes;
            }
            if (n.startsWith("getClient")
                    || n.startsWith("addClient")
                    || n.startsWith("removeClient")
                    || n.contains("Client")) {
                return clients;
            }
            if (n.contains("Group") || n.contains("group")) {
                return groups;
            }
            if (n.contains("Role") || n.contains("role")) {
                return roles;
            }
            return realms;
        }
    }
}
