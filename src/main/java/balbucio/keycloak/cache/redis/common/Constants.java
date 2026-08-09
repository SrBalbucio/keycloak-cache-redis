package balbucio.keycloak.cache.redis.common;

/**
 * Shared constants for the Redis cache extension.
 */
public final class Constants {

    /** One above Infinispan's {@code PROVIDER_ORDER} so our factories win when enabled. */
    public static final int PROVIDER_PRIORITY = 2;

    public static final String INFINISPAN_PROVIDER_ID = "infinispan";
    public static final String LEGACY_PROVIDER_ID = "legacy";
    public static final String DEFAULT_PROVIDER_ID = "default";

    public static final String VERSION_FIELD = "version";
    public static final String EXPIRATION_FIELD = "expiration";
    public static final String NULL_SENTINEL = "\u0000";

    public static final String NOTE_PREFIX = "n.";
    public static final String CLIENT_SESSION_PREFIX = "cs.";
    public static final String SET_PREFIX = "s.";

    public static final int CAS_MAX_RETRIES = 3;

    private Constants() {}
}
