package balbucio.keycloak.cache.redis.compatibility;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.sessions.StickySessionEncoderProvider;
import org.keycloak.sessions.StickySessionEncoderProviderFactory;

/**
 * Sessions live in shared Redis — do not attach a node route to sticky cookies.
 */
@AutoService(StickySessionEncoderProviderFactory.class)
public class DisabledStickySessionEncoderProvider
        implements StickySessionEncoderProviderFactory, StickySessionEncoderProvider, IsSupported {

    @Override
    public StickySessionEncoderProvider create(KeycloakSession session) {
        return this;
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
    public void setShouldAttachRoute(boolean shouldAttachRoute) {
        // always disabled
    }

    @Override
    public String encodeSessionId(String message, String sessionId) {
        return message;
    }

    @Override
    public boolean shouldAttachRoute() {
        return false;
    }

    @Override
    public String sessionIdRoute(String sessionId) {
        return null;
    }
}
