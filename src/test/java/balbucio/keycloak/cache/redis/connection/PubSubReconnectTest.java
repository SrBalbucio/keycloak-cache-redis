package balbucio.keycloak.cache.redis.connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Fase 1.2: política do listener de reconnect (primeira conexão ignora, demais reconciliam). */
class PubSubReconnectTest {

    @Test
    void everyConnectEventReconciles() {
        AtomicInteger runs = new AtomicInteger();
        RedisConnectionStateListener listener =
                PubSubReconnect.reconnectListener("owner", runs::incrementAndGet);

        // O listener é anexado após a conexão estabelecida, então não há "primeira
        // conexão" a ignorar: todo onRedisConnected já é uma reconexão genuína.
        assertDoesNotThrow(() -> listener.onRedisConnected(null, null));
        assertTrue(runs.get() == 1, "first observed connect must already reconcile");

        assertDoesNotThrow(() -> listener.onRedisConnected(null, null));
        assertTrue(runs.get() == 2, "every reconnect must reconcile");
    }

    @Test
    void disconnectNeverThrows() {
        RedisConnectionStateListener listener =
                PubSubReconnect.reconnectListener("owner", () -> {});
        assertDoesNotThrow(() -> listener.onRedisDisconnected(null));
    }

    @Test
    void failingActionDoesNotPropagate() {
        RedisConnectionStateListener listener =
                PubSubReconnect.reconnectListener(
                        "owner",
                        () -> {
                            throw new IllegalStateException("boom");
                        });

        assertDoesNotThrow(() -> listener.onRedisConnected(null, null));
        assertDoesNotThrow(
                () -> listener.onRedisConnected(null, null),
                "a failing reconciliation must not break the subscriber");
    }

    @Test
    void nullActionOnlyObserves() {
        RedisConnectionStateListener listener = PubSubReconnect.reconnectListener("owner", null);

        assertDoesNotThrow(() -> listener.onRedisConnected(null, null));
        assertDoesNotThrow(() -> listener.onRedisConnected(null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachAndDetachDelegateToConnection() {
        StatefulRedisPubSubConnection<String, String> connection =
                mock(StatefulRedisPubSubConnection.class);
        RedisConnectionStateListener listener =
                PubSubReconnect.reconnectListener("owner", null);

        assertTrue(PubSubReconnect.attach(connection, listener));
        verify(connection).addListener(listener);

        assertDoesNotThrow(() -> PubSubReconnect.detach(connection, listener));
        verify(connection).removeListener(listener);
    }

    @Test
    void attachWithNullsReturnsFalse() {
        assertFalse(PubSubReconnect.attach(null, PubSubReconnect.reconnectListener("o", null)));
        assertFalse(PubSubReconnect.attach(mock(StatefulRedisPubSubConnection.class), null));
        assertDoesNotThrow(() -> PubSubReconnect.detach(null, null));
    }
}
