package balbucio.keycloak.cache.redis.connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

/**
 * O boot do Keycloak nunca pode morrer porque o Redis está momentaneamente inalcançável
 * (DNS fora, Redis reiniciando): {@code init()} só valida config, o TCP conecta no primeiro
 * uso. Sem Docker: aponta para uma porta local fechada (refused rápido).
 */
class LettuceRedisClientSupportLazyTest {

    private String savedPrefix;

    @BeforeEach
    void savePrefix() {
        savedPrefix = RedisKeySpace.prefix();
    }

    @AfterEach
    void restorePrefix() {
        RedisKeySpace.configure(savedPrefix);
    }

    @Test
    void initDoesNotOpenConnections() {
        LettuceRedisClientSupport support = new LettuceRedisClientSupport(true);
        try {
            assertDoesNotThrow(
                    () -> support.init(unreachableScope(), "KC_SPI_TEST"),
                    "init must not touch the network: boot stays alive when Redis is down");
        } finally {
            support.close();
        }
    }

    @Test
    void firstUseFailsButCloseStaysSafe() {
        LettuceRedisClientSupport support = new LettuceRedisClientSupport(true);
        try {
            support.init(unreachableScope(), "KC_SPI_TEST");
            RedisConnectionProvider provider = support.asProvider();
            assertThrows(
                    RuntimeException.class,
                    provider::sync,
                    "first use must surface the Redis failure per-request, not at boot");
        } finally {
            assertDoesNotThrow(
                    support::close, "close after a failed connect must not throw");
        }
    }

    @Test
    void blankNodesStillFailFast() {
        LettuceRedisClientSupport support = new LettuceRedisClientSupport(true);
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> support.init(scope(Map.of()), "KC_SPI_TEST"),
                    "invalid config must still fail fast at init");
        } finally {
            support.close();
        }
    }

    /** Points at a closed local port: connect fails fast with refused (no Docker needed). */
    private static Config.Scope unreachableScope() {
        return scope(
                Map.of(
                        "nodes", "127.0.0.1:9",
                        "timeout", "500ms",
                        "database", "0"));
    }

    private static Config.Scope scope(Map<String, String> values) {
        return new Config.Scope() {
            @Override
            public String get(String name) {
                return values.get(name);
            }

            @Override
            public String get(String name, String defaultValue) {
                return values.getOrDefault(name, defaultValue);
            }

            @Override
            public String[] getArray(String name) {
                return new String[0];
            }

            @Override
            public Integer getInt(String name, Integer defaultValue) {
                String v = values.get(name);
                return v == null ? defaultValue : Integer.parseInt(v);
            }

            @Override
            public Long getLong(String name, Long defaultValue) {
                String v = values.get(name);
                return v == null ? defaultValue : Long.parseLong(v);
            }

            @Override
            public Boolean getBoolean(String name, Boolean defaultValue) {
                String v = values.get(name);
                return v == null ? defaultValue : Boolean.parseBoolean(v);
            }

            @Override
            public Config.Scope scope(String... scope) {
                return this;
            }

            @Override
            public Set<String> getPropertyNames() {
                return values.keySet();
            }

            @Override
            public Config.Scope root() {
                return this;
            }
        };
    }
}
