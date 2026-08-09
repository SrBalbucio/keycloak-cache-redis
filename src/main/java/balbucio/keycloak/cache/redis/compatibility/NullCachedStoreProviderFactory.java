package balbucio.keycloak.cache.redis.compatibility;

import balbucio.keycloak.cache.redis.common.Constants;
import balbucio.keycloak.cache.redis.common.IsSupported;
import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.authorization.store.PermissionTicketStore;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceServerStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.authorization.CachedStoreFactoryProvider;
import org.keycloak.models.cache.authorization.CachedStoreProviderFactory;

/**
 * Disables the Infinispan authorization cache by delegating directly to the local StoreFactory.
 */
@AutoService(CachedStoreProviderFactory.class)
public class NullCachedStoreProviderFactory implements CachedStoreProviderFactory, IsSupported {

    @Override
    public CachedStoreFactoryProvider create(KeycloakSession session) {
        StoreFactory local = session.getProvider(StoreFactory.class);
        return new DelegatingCachedStore(local);
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public String getId() {
        return Constants.DEFAULT_PROVIDER_ID;
    }

    @Override
    public int order() {
        return Constants.PROVIDER_PRIORITY;
    }

    private static final class DelegatingCachedStore implements CachedStoreFactoryProvider {
        private final StoreFactory delegate;

        private DelegatingCachedStore(StoreFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public ResourceStore getResourceStore() {
            return delegate.getResourceStore();
        }

        @Override
        public ResourceServerStore getResourceServerStore() {
            return delegate.getResourceServerStore();
        }

        @Override
        public ScopeStore getScopeStore() {
            return delegate.getScopeStore();
        }

        @Override
        public PolicyStore getPolicyStore() {
            return delegate.getPolicyStore();
        }

        @Override
        public PermissionTicketStore getPermissionTicketStore() {
            return delegate.getPermissionTicketStore();
        }

        @Override
        public void setReadOnly(boolean readOnly) {
            delegate.setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
