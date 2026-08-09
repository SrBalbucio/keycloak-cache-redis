package balbucio.keycloak.cache.redis.singleUseObject;

import java.util.Objects;

import balbucio.keycloak.cache.redis.Key;
import balbucio.keycloak.cache.redis.common.RedisKeySpace;

public record SingleUseObjectKey(String id) implements Key {

    @Override
    public String key() {
        return RedisKeySpace.key("single-use:" + id);
    }

    public static SingleUseObjectKey of(String id) {
        return new SingleUseObjectKey(Objects.requireNonNull(id));
    }
}
