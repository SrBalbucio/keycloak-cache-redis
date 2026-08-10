package balbucio.keycloak.cache.redis.userSession;

import java.util.Objects;

import balbucio.keycloak.cache.redis.Key;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public record AuthenticatedClientSessionKey(
        String realmId, String userSessionId, String clientId, boolean offline) implements Key {

    public static final String RELATIVE_PREFIX = "authenticated-client:";
    public static final String RELATIVE_OFFLINE_PREFIX = "authenticated-client-offline:";

    public String compoundId() {
        return userSessionId + "::" + clientId;
    }

    @Override
    public String key() {
        return RedisKeySpace.taggedKey(
                realmId, (offline ? RELATIVE_OFFLINE_PREFIX : RELATIVE_PREFIX) + compoundId());
    }

    public static AuthenticatedClientSessionKey of(
            String realmId, String userSessionId, String clientId, boolean offline) {
        return new AuthenticatedClientSessionKey(
                Objects.requireNonNull(realmId),
                Objects.requireNonNull(userSessionId),
                Objects.requireNonNull(clientId),
                offline);
    }
}
