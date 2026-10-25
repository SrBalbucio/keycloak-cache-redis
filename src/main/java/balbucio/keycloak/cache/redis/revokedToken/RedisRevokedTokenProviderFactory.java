package balbucio.keycloak.cache.redis.revokedToken;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RevokedTokenProvider;
import org.keycloak.models.RevokedTokenProviderFactory;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.provider.Provider;

/**
 * Shadows the embedded {@code infinispan} factory of the internal {@code revokedToken} SPI (same
 * id, higher order) so {@code KeycloakSession.revokedTokens()} resolves to Redis when the
 * extension is enabled. Delegates storage to the single-use-object provider resolved from the
 * session — the Redis one when this extension is on — exactly like the stock
 * {@code InfinispanRevokedTokenProviderFactory} delegates to its embedded provider.
 */
@AutoService(RevokedTokenProviderFactory.class)
public class RedisRevokedTokenProviderFactory implements RevokedTokenProviderFactory<RevokedTokenProvider>, IsSupported {

    @Override
    public RevokedTokenProvider create(KeycloakSession session) {
        return new RedisRevokedTokenProvider(session.getProvider(SingleUseObjectProvider.class));
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public String getId() {
        return Constants.INFINISPAN_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }

    @Override
    public java.util.Set<Class<? extends Provider>> dependsOn() {
        return java.util.Set.of(SingleUseObjectProvider.class);
    }
}
