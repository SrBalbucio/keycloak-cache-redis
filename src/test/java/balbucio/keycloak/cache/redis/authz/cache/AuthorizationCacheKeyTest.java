package balbucio.keycloak.cache.redis.authz.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import org.junit.jupiter.api.Test;

class AuthorizationCacheKeyTest {

    @Test
    void resourceKeysAreDistinctAndPrefixed() {
        String byId = AuthorizationCacheKey.resourceById("abc-123");
        String byName = AuthorizationCacheKey.resourceByName("rs-1", "album");

        assertTrue(byId.endsWith("authz:resource:abc-123"));
        assertTrue(byName.endsWith("authz:resource:rs-1:name:album"));
    }

    @Test
    void policyByResourceIncludesBothIds() {
        String key = AuthorizationCacheKey.policyByResource("rs-1", "res-9");
        assertTrue(key.endsWith("authz:policy:rs-1:resource:res-9"));
    }

    @Test
    void generationKeyIsPerResourceServer() {
        String gen1 = AuthorizationCacheKey.generation("rs-1");
        String gen2 = AuthorizationCacheKey.generation("rs-2");

        assertTrue(gen1.endsWith("authz:rs-gen:rs-1"));
        assertTrue(gen2.endsWith("authz:rs-gen:rs-2"));
    }

    @Test
    void resourceServerByClientDistinctFromById() {
        String byId = AuthorizationCacheKey.resourceServerById("rs-id");
        String byClient = AuthorizationCacheKey.resourceServerByClient("client-42");

        assertTrue(byId.endsWith("authz:resource-server:rs-id"));
        assertTrue(byClient.endsWith("authz:resource-server:client:client-42"));
    }

    @Test
    void allKeysShareGlobalPrefix() {
        RedisKeySpace.configure("test-kc:");
        try {
            String id = AuthorizationCacheKey.resourceById("x");
            String gen = AuthorizationCacheKey.generation("y");
            String scope = AuthorizationCacheKey.scopeByName("rs", "s");

            assertTrue(id.startsWith("test-kc:"), "resource key should carry prefix: " + id);
            assertTrue(gen.startsWith("test-kc:"), "generation key should carry prefix: " + gen);
            assertTrue(scope.startsWith("test-kc:"), "scope key should carry prefix: " + scope);
        } finally {
            RedisKeySpace.configure(null);
        }
    }

    @Test
    void scopeAndResourceKeysDoNotCollide() {
        assertNotEquals(
                AuthorizationCacheKey.resourceById("shared-id"),
                AuthorizationCacheKey.scopeById("shared-id"));
    }
}
