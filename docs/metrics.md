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
