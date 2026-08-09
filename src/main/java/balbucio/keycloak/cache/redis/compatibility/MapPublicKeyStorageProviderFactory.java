package balbucio.keycloak.cache.redis.compatibility;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.keys.PublicKeyStorageProvider;
import org.keycloak.keys.PublicKeyStorageProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@AutoService(PublicKeyStorageProviderFactory.class)
public class MapPublicKeyStorageProviderFactory
        implements PublicKeyStorageProviderFactory<PublicKeyStorageProvider>, IsSupported {

    private MapPublicKeyStorageProvider provider;

    @Override
    public PublicKeyStorageProvider create(KeycloakSession session) {
        return provider;
    }

    @Override
    public void init(Config.Scope config) {
        provider = new MapPublicKeyStorageProvider();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {
        if (provider != null) {
            provider.close();
        }
    }

    @Override
    public String getId() {
        return Constants.INFINISPAN_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }
}
