# Métricas

Com métricas do Keycloak habilitadas (`/metrics`), a extensão registra:

## Latência Lettuce

`MicrometerCommandLatencyRecorder` no cliente Lettuce, configurado na inicialização da conexão Redis.

## Contadores de operação

Métrica: `vendor.lettuce.cache`

Tags:

| Tag | Valores |
|-----|---------|
| `cache` | `userSession`, `clientSession`, `authSession`, `loginFailure`, `singleUse`, `cluster`, `authz`, `authzGen`, `publicKeys`, `entity`, `generic` |
| `op` | Redis cmds: `HGETALL`, `HSETEX`, `HSET`, `SADD`, `SREM`, `DEL`, `EVAL`, `PUBLISH`, `SMEMBERS`, `GET`, `SET`, `INCR` |
| `op` (outcomes) | `HIT`, `MISS`, `ERROR` (authz L1/L2); `CAS_RETRY`, `CAS_FAIL` (`generic`) |

Implementação: `RedisMetrics`. Falhas ao incrementar contadores são engolidas — métricas nunca quebram o request path.

### Outcomes

- **authz `HIT` / `MISS` / `ERROR`**: resultado de leitura no cache de Authorization Services (L1 local ou L2 Redis). `ERROR` cobre falha Redis/deser com fail-open (retorno `null`).
- **generic `CAS_RETRY`**: conflito de versão no commit Lua (rebase + nova tentativa).
- **generic `CAS_FAIL`**: esgotaram-se as tentativas de CAS sem escrita bem-sucedida.

## Exemplo de uso

Com Prometheus/Micrometer scrape no endpoint de métricas do Keycloak:

- taxa de `HIT` vs `MISS`/`ERROR` em `cache=authz`;
- taxa de `CAS_RETRY` / `CAS_FAIL` em `cache=generic`;
- volume de `PUBLISH` no canal de cluster;
- `INCR` em `authzGen` como sinal de invalidação de Authorization Services.

## Cluster bus (Fase 0)

Transporte da invalidação dos caches locais Infinispan com `KC_CACHE=local` via
`kc:cluster:events`. Ver a matriz completa em [cluster-event-matrix.md](cluster-event-matrix.md).

Métrica: `vendor.lettuce.cluster.events` (contador, tags `eventKey` + `outcome`)

| `outcome` | Significado |
|-----------|-------------|
| `sent` | `notify()` publicou no Redis |
| `delivered` | mensagem desserializada e despachada para ≥1 listener local |
| `self_ignored` | eco próprio descartado (`ignoreSender=true`) |
| `dropped_no_listener` | chegou mas nenhum listener registrado para o `eventKey` neste nó |
| `dropped_null_events` | envelope sem lista de eventos |
| `deser_error` | falha de desserialização ou exceção no dispatch (nunca derruba o subscriber) |
| `publish_error` | falha ao publicar no Redis |
| `reconnected` | subscrição PUBSUB reconectada após outage (Fase 1.2; invalidações do outage perdidas) |
| `resync_cleared` | L1 próprio limpo como reconciliação no reconnect (Fase 1.2; ausente no canal `cluster`, sem L1) |

Métrica: `vendor.lettuce.cluster.lag` (timer, tag `eventKey`)

- Lag publish→deliver por `eventKey`, a partir do campo `sentAtMillis` do envelope
  (backward-compatible: mensagens antigas sem o campo não geram amostra).
- Uso: `histogram_quantile(0.99, ...)` por `eventKey` para definir o SLO de propagação.

Métrica: `vendor.lettuce.cluster.task` (timer, tags `task` + `outcome`)

| `outcome` | Significado |
|-----------|-------------|
| `finished` | waiter observou conclusão dentro do timeout |
| `timeout` | waiter esgotou `taskTimeoutInSeconds` sem observar conclusão (ex: holder morreu sem `publish task-finished`) |

Implementação: `RedisMetrics` (`recordClusterEvent/recordClusterLag/recordClusterTask`).
Todas fail-open. O contador legado `vendor.lettuce.cache{cache="cluster",op="PUBLISH"}`
foi mantido por compatibilidade.
