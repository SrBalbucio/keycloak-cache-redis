package balbucio.keycloak.cache.redis.connection;

import balbucio.keycloak.cache.redis.RedisMetrics;
import io.lettuce.core.RedisConnectionStateAdapter;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.jboss.logging.Logger;

/**
 * Fase 1.2: reconciliação conservadora no reconnect do PUBSUB.
 *
 * <p>Redis PUBSUB não tem histórico: invalidações publicadas enquanto este nó estava
 * desconectado são perdidas para sempre (sem replay). Quando o Lettuce reconecta, a
 * subscrição volta sozinha, mas o L1 local pode ter servido stale durante a janela. A
 * resposta segura — sem replicar valores nem tocar nos caches stock do Infinispan — é
 * limpar o L1 <em>próprio</em> (authz LRU, public-keys L1) no reconnect: a próxima leitura
 * recarrega do Redis L2 (frio, mas correto). Caches stock (`realms`/`users`) continuam
 * convergindo sob demanda via listeners do core no próximo evento; limpá-los à força
 * causaria thundering-herd no banco e por isso é explicitamente fora de escopo.
 *
 * <p>Tudo aqui é fail-open: o hook nunca quebra o caminho de subscrição.
 */
public final class PubSubReconnect {

    private static final Logger LOG = Logger.getLogger(PubSubReconnect.class);

    private PubSubReconnect() {}

    /**
     * Cria o listener de reconnect para {@code owner} (ex.: {@code "authz-lru"}).
     * Todo evento é tratado como reconnect: o listener é sempre anexado <em>após</em> a
     * conexão já estabelecida ({@code connectPubSub()} + subscribe), então o primeiro
     * {@code onRedisConnected} observado já é uma reconexão genuína (não há "primeira
     * conexão" a ignorar — o L1 pode ter acumulado stale durante o outage).
     * Cada evento registra métrica, roda {@code onReconnect} (tipicamente limpar o L1) e
     * registra a conclusão. {@code onReconnect} pode ser {@code null} (só observa + métrica,
     * como no canal {@code cluster:events}, onde não há L1 próprio).
     */
    public static RedisConnectionStateListener reconnectListener(String owner, Runnable onReconnect) {
        return new RedisConnectionStateAdapter() {
            @Override
            public void onRedisConnected(
                    io.lettuce.core.RedisChannelHandler<?, ?> connection,
                    java.net.SocketAddress socketAddress) {
                LOG.infof(
                        "%s PUBSUB reconnected — invalidations published during the outage were lost; reconciling local state",
                        owner);
                RedisMetrics.recordClusterEvent(owner, RedisMetrics.ClusterEvent.RECONNECTED);
                if (onReconnect != null) {
                    try {
                        onReconnect.run();
                        RedisMetrics.recordClusterEvent(owner, RedisMetrics.ClusterEvent.RESYNC_CLEARED);
                    } catch (RuntimeException e) {
                        LOG.debugf(e, "Reconnect reconciliation failed for %s", owner);
                    }
                }
            }

            @Override
            public void onRedisDisconnected(io.lettuce.core.RedisChannelHandler<?, ?> connection) {
                LOG.debugf("%s PUBSUB disconnected — local L1 may go stale until reconnect", owner);
            }
        };
    }

    /** Anexa {@code listener} à conexão; devolve {@code false} (sem exceção) se indisponível. */
    public static boolean attach(
            StatefulRedisPubSubConnection<String, String> connection,
            RedisConnectionStateListener listener) {
        if (connection == null || listener == null) {
            return false;
        }
        try {
            connection.addListener(listener);
            return true;
        } catch (RuntimeException e) {
            LOG.debugf(e, "Failed to attach PUBSUB reconnect listener");
            return false;
        }
    }

    /** Remove {@code listener} da conexão; fail-open. */
    public static void detach(
            StatefulRedisPubSubConnection<String, String> connection,
            RedisConnectionStateListener listener) {
        if (connection == null || listener == null) {
            return;
        }
        try {
            connection.removeListener(listener);
        } catch (RuntimeException e) {
            LOG.debugf(e, "Failed to detach PUBSUB reconnect listener");
        }
    }
}
