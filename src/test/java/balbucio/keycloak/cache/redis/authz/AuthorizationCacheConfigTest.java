package balbucio.keycloak.cache.redis.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuthorizationCacheConfigTest {

    @AfterEach
    void cleanup() {
        System.clearProperty(AuthorizationCacheConfig.PROP_ENABLED);
        System.clearProperty(AuthorizationCacheConfig.PROP_TTL);
        System.clearProperty(AuthorizationCacheConfig.PROP_PT_TTL);
        System.clearProperty(AuthorizationCacheConfig.PROP_GEN_TTL);
        System.clearProperty(AuthorizationCacheConfig.PROP_LRU_ENABLED);
        System.clearProperty(AuthorizationCacheConfig.PROP_LRU_MAX_SIZE);
        System.clearProperty(AuthorizationCacheConfig.PROP_LRU_TTL);
    }

    @Test
    void defaultsWhenNothingSet() {
        AuthorizationCacheConfig config = AuthorizationCacheConfig.load();
        assertTrue(config.isEnabled());
        assertEquals(1800L, config.getTtlSeconds());
        assertEquals(300L, config.getPermissionTicketTtlSeconds());
        assertEquals(604800L, config.getGenTtlSeconds());
        assertFalse(config.isLruEnabled());
        assertEquals(1000, config.getLruMaxSize());
        assertEquals(30L, config.getLruTtlSeconds());
    }

    @Test
    void readsEnabledFromSystemProperty() {
        System.setProperty(AuthorizationCacheConfig.PROP_ENABLED, "false");
        assertFalse(AuthorizationCacheConfig.load().isEnabled());
    }

    @Test
    void readsTtlFromSystemProperty() {
        System.setProperty(AuthorizationCacheConfig.PROP_TTL, "600");
        assertEquals(600L, AuthorizationCacheConfig.load().getTtlSeconds());
    }

    @Test
    void invalidTtlFallsBackToDefault() {
        System.setProperty(AuthorizationCacheConfig.PROP_TTL, "not-a-number");
        assertEquals(1800L, AuthorizationCacheConfig.load().getTtlSeconds());
    }

    @Test
    void readsLocalLruProperties() {
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_ENABLED, "true");
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_MAX_SIZE, "77");
        System.setProperty(AuthorizationCacheConfig.PROP_LRU_TTL, "9");

        AuthorizationCacheConfig config = AuthorizationCacheConfig.load();
        assertTrue(config.isLruEnabled());
        assertEquals(77, config.getLruMaxSize());
        assertEquals(9L, config.getLruTtlSeconds());
    }
}
