package balbucio.keycloak.cache.redis.authSession;

import java.util.Objects;

import balbucio.keycloak.cache.redis.Key;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public record RootAuthenticationSessionKey(String realmId, String id) implements Key {

    public static final String RELATIVE_PREFIX = "auth-session:";

    @Override
    public String key() {
        return RedisKeySpace.taggedKey(realmId, RELATIVE_PREFIX + id);
    }

    public static RootAuthenticationSessionKey of(String realmId, String id) {
        return new RootAuthenticationSessionKey(
                Objects.requireNonNull(realmId), Objects.requireNonNull(id));
    }
}
