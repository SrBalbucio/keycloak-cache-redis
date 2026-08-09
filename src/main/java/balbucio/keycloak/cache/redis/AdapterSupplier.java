package balbucio.keycloak.cache.redis;

/**
 * Builds a typed adapter from a Redis key and {@link MapEntity}.
 */
@FunctionalInterface
public interface AdapterSupplier<K extends Key, A extends MapEntity> {
    A create(K key, MapEntity entity);
}
