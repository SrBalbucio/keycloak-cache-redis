package balbucio.keycloak.cache.redis.connection;

import balbucio.keycloak.cache.redis.common.CommunityProfiles;
import com.google.auto.service.AutoService;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Optional second Redis connection for Authorization Services cache.
 *
 * <p>Configured via {@code KC_SPI_REDIS_CONNECTION_AUTHZ_*}. When {@code nodes} is unset/blank, this
 * factory is unsupported and consumers fall back to {@code default}.
 */
@AutoService(RedisConnectionProviderFactory.class)
public class AuthzRedisConnectionProviderFactory
        implements RedisConnectionProviderFactory, EnvironmentDependentProviderFactory {

    public static final String PROVIDER_ID = "authz";

    private static final Logger LOG = Logger.getLogger(AuthzRedisConnectionProviderFactory.class);

    private LettuceRedisClientSupport support;
    private boolean active;

    @Override
    public RedisConnectionProvider create(KeycloakSession session) {
        return support != null ? support.asProvider() : null;
    }

    @Override
    public void init(Config.Scope config) {
        String nodes = config.get("nodes");
        if (nodes == null || nodes.isBlank()) {
            active = false;
            LOG.debug("Authz Redis connection not configured (nodes empty) — using default connection");
            return;
        }
        support = new LettuceRedisClientSupport(false);
        support.init(config, "KC_SPI_REDIS_CONNECTION_AUTHZ");
        active = true;
        LOG.info("Authz Redis connection factory active (separate from sessions/default)");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (support != null) {
            support.close();
            support = null;
        }
        active = false;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        if (!CommunityProfiles.isRedisCacheEnabled()) {
            return false;
        }
        // Keycloak may call isSupported before init; check config nodes directly.
        String nodes = config != null ? config.get("nodes") : null;
        return nodes != null && !nodes.isBlank();
    }

    boolean isActive() {
        return active;
    }
}
