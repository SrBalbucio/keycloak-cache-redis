# Conexão Redis

## SPI `redisConnection`

A extensão define um SPI interno de conexão compartilhado por sessões, clustering e authz.

| Classe | Papel |
|--------|-------|
| `RedisConnectionSpi` | Registro do SPI |
| `RedisConnectionProvider` | Acesso session-scoped a sync / async / pubsub |
| `DefaultRedisConnectionProviderFactory` | Ciclo de vida do cliente Lettuce + carga do script CAS |
| `RedisSync` / `LettuceRedisSync` | API síncrona independente do modo |
| `RedisAsync` / `LettuceRedisAsync` | API assíncrona |
| `RedisMode` | `STANDALONE`, `SENTINEL`, `CLUSTER` |
| `RedisKeySpace` | Prefixo global de chaves |

## Modos

### standalone

Um único nó Redis/Valkey. Usa o primeiro item de `NODES`.

```bash
KC_SPI_REDIS_CONNECTION_DEFAULT_MODE=standalone
KC_SPI_REDIS_CONNECTION_DEFAULT_NODES=valkey:6379
```

### sentinel

Lista de sentinels em `NODES` + `MASTER_NAME` obrigatório.

```bash
KC_SPI_REDIS_CONNECTION_DEFAULT_MODE=sentinel
KC_SPI_REDIS_CONNECTION_DEFAULT_NODES=sentinel1:26379,sentinel2:26379
KC_SPI_REDIS_CONNECTION_DEFAULT_MASTER_NAME=mymaster
```

### cluster

Lista de seed nodes em `NODES`. Em Redis Cluster:

- o índice de database é forçado a `0`;
- `MULTI/EXEC` para atualização de índices secundários é desativado;
- índices ficam com consistência eventual (comandos individuais).

```bash
KC_SPI_REDIS_CONNECTION_DEFAULT_MODE=cluster
KC_SPI_REDIS_CONNECTION_DEFAULT_NODES=redis1:6379,redis2:6379,redis3:6379
```

## Prefixo de chaves

Configurado via:

1. SPI `keyPrefix` (`KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX`), ou
2. env `KC_REDIS_KEY_PREFIX`

O sufixo `:` é aplicado automaticamente quando necessário. Exemplo: `kc` → todas as chaves começam com `kc:`.

## Segurança e timeouts

- `SSL=true` habilita TLS no cliente Lettuce.
- `USERNAME` / `PASSWORD` são opcionais (ACL Redis).
- `TIMEOUT` aceita formatos como `2000`, `2s`, `500ms`.

## Observabilidade

Na inicialização da conexão, o cliente Lettuce registra `MicrometerCommandLatencyRecorder` no registry global do Keycloak. Contadores de operação ficam em [Métricas](metrics.md).
