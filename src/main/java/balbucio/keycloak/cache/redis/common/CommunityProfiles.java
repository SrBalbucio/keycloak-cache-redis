package balbucio.keycloak.cache.redis.common;

/**
 * Feature toggle for the community Redis cache extension.
 *
 * <p>Enabled when either {@code KC_COMMUNITY_REDIS_CACHE_ENABLED=true} or
 * {@code kc.community.redis.cache.enabled=true}.
 */
public final class CommunityProfiles {

    public static final String ENV_ENABLED = "KC_COMMUNITY_REDIS_CACHE_ENABLED";
    public static final String PROP_ENABLED = "kc.community.redis.cache.enabled";

    private CommunityProfiles() {}

    public static boolean isRedisCacheEnabled() {
        String env = System.getenv(ENV_ENABLED);
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env.trim());
        }
        return Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "false"));
    }
}
