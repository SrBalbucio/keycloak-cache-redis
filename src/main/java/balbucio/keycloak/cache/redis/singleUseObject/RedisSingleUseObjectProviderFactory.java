package balbucio.keycloak.cache.redis.singleUseObject;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.SingleUseObjectProviderFactory;

@AutoService(SingleUseObjectProviderFactory.class)
public class RedisSingleUseObjectProviderFactory
        implements SingleUseObjectProviderFactory<SingleUseObjectProvider>, IsSupported {

    @Override
    public SingleUseObjectProvider create(KeycloakSession session) {
        return new RedisSingleUseObjectProvider(session);
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
}
