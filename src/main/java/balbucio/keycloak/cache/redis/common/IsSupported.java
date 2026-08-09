package balbucio.keycloak.cache.redis.common;

import org.keycloak.Config;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Marker for factories that should only load when the Redis cache feature toggle is on.
 */
public interface IsSupported extends EnvironmentDependentProviderFactory {

    @Override
    default boolean isSupported(Config.Scope config) {
        return CommunityProfiles.isRedisCacheEnabled();
    }
}
