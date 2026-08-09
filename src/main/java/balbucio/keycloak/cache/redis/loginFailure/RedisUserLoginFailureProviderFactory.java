package balbucio.keycloak.cache.redis.loginFailure;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserLoginFailureProviderFactory;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;

@AutoService(UserLoginFailureProviderFactory.class)
public class RedisUserLoginFailureProviderFactory
        implements UserLoginFailureProviderFactory<UserLoginFailureProvider>,
                IsSupported,
                ProviderEventListener {

    @Override
    public UserLoginFailureProvider create(KeycloakSession session) {
        return new RedisUserLoginFailureProvider(session);
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(this);
    }

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
    public void onEvent(ProviderEvent event) {
        if (event instanceof UserModel.UserRemovedEvent userRemoved) {
            UserLoginFailureProvider provider =
                    userRemoved.getKeycloakSession().getProvider(UserLoginFailureProvider.class, getId());
            if (provider != null) {
                provider.removeUserLoginFailure(userRemoved.getRealm(), userRemoved.getUser().getId());
            }
        }
    }
}
