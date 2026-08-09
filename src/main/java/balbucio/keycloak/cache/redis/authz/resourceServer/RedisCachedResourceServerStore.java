package balbucio.keycloak.cache.redis.authz.resourceServer;

import balbucio.keycloak.cache.redis.authz.AuthorizationInvalidation;
import balbucio.keycloak.cache.redis.authz.cache.AuthorizationCacheKey;
import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedResourceServer;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;

/**
 * Cache-aside {@link ResourceServerStore} wrapping the JPA delegate.
 *
 * <p>{@code findById} and {@code findByClient} are cached (warm lookups during policy evaluation
 * setup). {@code create} and {@code delete} trigger double-INCR invalidation.
 */
public class RedisCachedResourceServerStore implements ResourceServerStore {

    private final KeycloakSession session;
    private final ResourceServerStore delegate;
    private final RedisAuthorizationCache cache;

    public RedisCachedResourceServerStore(
            KeycloakSession session, ResourceServerStore delegate, RedisAuthorizationCache cache) {
        this.session = session;
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public ResourceServer create(ClientModel client) {
        ResourceServer resourceServer = delegate.create(client);
        if (resourceServer != null) {
            AuthorizationInvalidation.invalidate(session, cache, resourceServer.getId());
        }
        return resourceServer;
    }

    @Override
    public void delete(ClientModel client) {
        ResourceServer existing = delegate.findByClient(client);
        if (existing != null) {
            cache.remove(AuthorizationCacheKey.resourceServerById(existing.getId()));
            cache.remove(AuthorizationCacheKey.resourceServerByClient(client.getId()));
            AuthorizationInvalidation.invalidate(session, cache, existing.getId());
        }
        delegate.delete(client);
    }

    @Override
    public ResourceServer findById(String id) {
        if (id == null) {
            return null;
        }
        long gen = cache.currentGeneration(id);
        CachedResourceServer cached =
                cache.get(AuthorizationCacheKey.resourceServerById(id), gen, CachedResourceServer.class);
        if (cached != null) {
            return new ResourceServerAdapter(session, cached, delegate, cache);
        }

        ResourceServer resourceServer = delegate.findById(id);
        if (resourceServer == null) {
            return null;
        }

        CachedResourceServer snapshot = CachedResourceServer.from(resourceServer);
        cache.put(AuthorizationCacheKey.resourceServerById(id), gen, snapshot);
        return new ResourceServerAdapter(session, snapshot, delegate, cache);
    }

    @Override
    public ResourceServer findByClient(ClientModel client) {
        if (client == null || client.getId() == null) {
            return delegate.findByClient(client);
        }
        String clientId = client.getId();
        long gen = cache.currentGeneration(clientId);
        CachedResourceServer cached =
                cache.get(AuthorizationCacheKey.resourceServerByClient(clientId), gen, CachedResourceServer.class);
        if (cached != null) {
            return new ResourceServerAdapter(session, cached, delegate, cache);
        }

        ResourceServer resourceServer = delegate.findByClient(client);
        if (resourceServer == null) {
            return null;
        }

        CachedResourceServer snapshot = CachedResourceServer.from(resourceServer);
        cache.put(AuthorizationCacheKey.resourceServerByClient(clientId), gen, snapshot);
        return new ResourceServerAdapter(session, snapshot, delegate, cache);
    }
}
