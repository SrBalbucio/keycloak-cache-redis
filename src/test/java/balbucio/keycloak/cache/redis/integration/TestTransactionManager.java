package balbucio.keycloak.cache.redis.integration;

import java.util.ArrayList;
import java.util.List;

import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;

/**
 * Minimal in-memory {@link KeycloakTransactionManager} that commits every enlisted
 * {@link KeycloakTransaction} on {@link #commit()} — enough for the Redis changelog unit-of-work
 * used by the providers under test.
 */
public class TestTransactionManager implements KeycloakTransactionManager {

    private final List<KeycloakTransaction> enlisted = new ArrayList<>();
    private final List<KeycloakTransaction> afterCompletion = new ArrayList<>();
    private boolean rollbackOnly;

    @Override
    public JTAPolicy getJTAPolicy() {
        return JTAPolicy.NOT_SUPPORTED;
    }

    @Override
    public void setJTAPolicy(JTAPolicy jtaPolicy) {}

    @Override
    public void enlist(KeycloakTransaction transaction) {
        enlisted.add(transaction);
    }

    @Override
    public void enlistAfterCompletion(KeycloakTransaction transaction) {
        afterCompletion.add(transaction);
    }

    @Override
    public void enlistPrepare(KeycloakTransaction transaction) {
        enlisted.add(transaction);
    }

    @Override
    public void begin() {}

    @Override
    public void commit() {
        for (KeycloakTransaction transaction : enlisted) {
            transaction.begin();
            transaction.commit();
        }
        for (KeycloakTransaction transaction : afterCompletion) {
            transaction.begin();
            transaction.commit();
        }
        enlisted.clear();
        afterCompletion.clear();
        rollbackOnly = false;
    }

    @Override
    public void rollback() {
        for (KeycloakTransaction transaction : enlisted) {
            transaction.begin();
            transaction.rollback();
        }
        for (KeycloakTransaction transaction : afterCompletion) {
            transaction.begin();
            transaction.rollback();
        }
        enlisted.clear();
        afterCompletion.clear();
        rollbackOnly = false;
    }

    @Override
    public void setRollbackOnly() {
        rollbackOnly = true;
    }

    @Override
    public boolean getRollbackOnly() {
        return rollbackOnly;
    }

    @Override
    public boolean isActive() {
        return true;
    }
}
