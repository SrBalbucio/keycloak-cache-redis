package balbucio.keycloak.cache.redis.connection;

import java.util.Locale;

public enum RedisMode {
    STANDALONE,
    SENTINEL,
    CLUSTER;

    public static RedisMode from(String value) {
        if (value == null || value.isBlank()) {
            return STANDALONE;
        }
        return RedisMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
