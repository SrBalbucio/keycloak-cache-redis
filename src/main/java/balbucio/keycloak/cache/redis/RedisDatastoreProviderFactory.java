package balbucio.keycloak.cache.redis;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.DatastoreProviderFactory;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;

@AutoService(DatastoreProviderFactory.class)
public class RedisDatastoreProviderFactory extends DefaultDatastoreProviderFactory implements IsSupported {

    @Override
    public DatastoreProvider create(KeycloakSession session) {
        return new RedisDatastoreProvider(this, session);
    }

    @Override
    public String getId() {
        return Constants.LEGACY_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }
}
