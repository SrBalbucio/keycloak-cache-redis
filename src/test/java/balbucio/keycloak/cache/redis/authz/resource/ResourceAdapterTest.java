package balbucio.keycloak.cache.redis.authz.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import balbucio.keycloak.cache.redis.authz.cache.RedisAuthorizationCache;
import balbucio.keycloak.cache.redis.authz.model.CachedResource;
import org.junit.jupiter.api.Test;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.cache.authorization.CachedStoreFactoryProvider;

class ResourceAdapterTest {

    @Test
    void getScopesResolvesCachedScopeIdsOnCacheHit() {
        ResourceServer resourceServer = mock(ResourceServer.class);
        when(resourceServer.getId()).thenReturn("rs-1");

        Scope scopeA = mock(Scope.class);
        when(scopeA.getId()).thenReturn("scope-a");
        Scope scopeB = mock(Scope.class);
        when(scopeB.getId()).thenReturn("scope-b");

        ScopeStore scopeStore = mock(ScopeStore.class);
        when(scopeStore.findById(eq(resourceServer), eq("scope-a"))).thenReturn(scopeA);
        when(scopeStore.findById(eq(resourceServer), eq("scope-b"))).thenReturn(scopeB);

        CachedStoreFactoryProvider factory = mock(CachedStoreFactoryProvider.class);
        when(factory.getScopeStore()).thenReturn(scopeStore);

        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(CachedStoreFactoryProvider.class)).thenReturn(factory);

        CachedResource cached = new CachedResource();
        cached.setId("res-1");
        cached.setName("photo");
        cached.setResourceServerId("rs-1");
        cached.setScopeIds(List.of("scope-a", "scope-b"));
        cached.setUris(new TreeSet<>(Set.of("/photos")));

        ResourceAdapter adapter =
                new ResourceAdapter(
                        session,
                        cached,
                        resourceServer,
                        mock(ResourceStore.class),
                        mock(RedisAuthorizationCache.class));

        List<Scope> scopes = adapter.getScopes();
        assertEquals(2, scopes.size());
        assertEquals("scope-a", scopes.get(0).getId());
        assertEquals("scope-b", scopes.get(1).getId());
    }

    @Test
    void getScopesReturnsEmptyWhenNoScopeIds() {
        ResourceServer resourceServer = mock(ResourceServer.class);
        KeycloakSession session = mock(KeycloakSession.class);

        CachedResource cached = new CachedResource();
        cached.setId("res-2");
        cached.setResourceServerId("rs-1");
        cached.setScopeIds(List.of());

        ResourceAdapter adapter =
                new ResourceAdapter(
                        session,
                        cached,
                        resourceServer,
                        mock(ResourceStore.class),
                        mock(RedisAuthorizationCache.class));

        assertTrue(adapter.getScopes().isEmpty());
    }

    @Test
    void getScopesSkipsMissingScopes() {
        ResourceServer resourceServer = mock(ResourceServer.class);
        Scope scopeA = mock(Scope.class);
        when(scopeA.getId()).thenReturn("scope-a");

        ScopeStore scopeStore = mock(ScopeStore.class);
        when(scopeStore.findById(eq(resourceServer), eq("scope-a"))).thenReturn(scopeA);
        when(scopeStore.findById(eq(resourceServer), eq("gone"))).thenReturn(null);

        CachedStoreFactoryProvider factory = mock(CachedStoreFactoryProvider.class);
        when(factory.getScopeStore()).thenReturn(scopeStore);

        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(CachedStoreFactoryProvider.class)).thenReturn(factory);

        CachedResource cached = new CachedResource();
        cached.setId("res-3");
        cached.setResourceServerId("rs-1");
        cached.setScopeIds(List.of("scope-a", "gone"));

        ResourceAdapter adapter =
                new ResourceAdapter(
                        session,
                        cached,
                        resourceServer,
                        mock(ResourceStore.class),
                        mock(RedisAuthorizationCache.class));

        List<Scope> scopes = adapter.getScopes();
        assertEquals(1, scopes.size());
        assertEquals("scope-a", scopes.get(0).getId());
    }
}
