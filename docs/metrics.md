# Métricas

Com métricas do Keycloak habilitadas (`/metrics`), a extensão registra:

## Latência Lettuce

`MicrometerCommandLatencyRecorder` no cliente Lettuce, configurado na inicialização da conexão Redis.

## Contadores de operação

Métrica: `vendor.lettuce.cache`

Tags:

| Tag | Valores |
|-----|---------|
| `cache` | `userSession`, `clientSession`, `authSession`, `loginFailure`, `singleUse`, `cluster`, `authz`, `authzGen`, `generic` |
| `op` | `HGETALL`, `HSETEX`, `HSET`, `SADD`, `SREM`, `DEL`, `EVAL`, `PUBLISH`, `SMEMBERS`, `GET`, `SET`, `INCR` |

Implementação: `RedisMetrics`. Falhas ao incrementar contadores são engolidas — métricas nunca quebram o request path.

## Exemplo de uso

Com Prometheus/Micrometer scrape no endpoint de métricas do Keycloak:

- taxa de `HGETALL` vs `EVAL` por cache;
- volume de `PUBLISH` no canal de cluster;
- `INCR` em `authzGen` como sinal de invalidação de Authorization Services.
