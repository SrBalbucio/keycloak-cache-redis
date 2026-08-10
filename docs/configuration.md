# Configuração

## Feature flag e cache Keycloak

| Variável / propriedade | Descrição | Valor típico |
|------------------------|-----------|--------------|
| `KC_COMMUNITY_REDIS_CACHE_ENABLED` | Liga a extensão (obrigatório) | `true` |
| `kc.community.redis.cache.enabled` | Alternativa via system property | `true` |
| `KC_CACHE` | Deve ser `local` (sem Infinispan distribuído) | `local` |

Sem o feature flag, as factories Redis reportam `isSupported=false` e o Keycloak usa os providers padrão.

## Conexão Redis

Prefixo SPI: `KC_SPI_REDIS_CONNECTION_DEFAULT_*`

| Variável | Descrição | Default / exemplo |
|----------|-----------|-------------------|
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MODE` | `standalone` \| `sentinel` \| `cluster` | `standalone` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_NODES` | `host:port` separados por vírgula | `redis:6379` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MASTER_NAME` | Nome do master (obrigatório em sentinel) | `mymaster` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_SSL` | TLS | `false` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_SSL_VERIFY_PEER` | Verificar certificado TLS (com SSL) | `true` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_USERNAME` | Usuário (opcional) | |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_PASSWORD` | Senha (opcional) | |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_TIMEOUT` | Timeout (`2000`, `2s`, `500ms`) | `2000ms` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_DATABASE` | Índice lógico (standalone/sentinel) | `0` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX` | Prefixo de todas as chaves | `kc` → `kc:` |
| `KC_REDIS_KEY_PREFIX` | Alias de env para o prefixo | `kc` |

Com prefixo `kc`, as chaves ficam como `kc:user-session:<id>`, `kc:auth-session:<id>`, `kc:cluster:events`, `kc:authz:...`, etc.

Detalhes dos modos: [Conexão Redis](connection.md).

## Authentication sessions

| Propriedade SPI | Descrição | Default |
|-----------------|-----------|---------|
| `authSessionsLimit` | Limite de tabs por root auth session | `300` |

## Authorization Services

Lidas de env ou system properties (env tem prioridade).

| Variável | Propriedade | Descrição | Default |
|----------|-------------|-----------|---------|
| `KC_CACHE_REDIS_AUTHZ_ENABLED` | `kc.cache.redis.authz.enabled` | Liga/desliga o cache de authz | `true` |
| `KC_CACHE_REDIS_AUTHZ_TTL_SECONDS` | `kc.cache.redis.authz.ttl-seconds` | TTL das entries | `1800` (30 min) |
| `KC_CACHE_REDIS_AUTHZ_PERMISSION_TICKET_TTL_SECONDS` | `kc.cache.redis.authz.permission-ticket-ttl-seconds` | TTL de permission tickets | `300` (5 min) |
| `KC_CACHE_REDIS_AUTHZ_GEN_TTL_SECONDS` | `kc.cache.redis.authz.gen-ttl-seconds` | TTL da chave de geração | `604800` (7 dias) |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_ENABLED` | `kc.cache.redis.authz.local-lru.enabled` | LRU local por nó | `false` |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_MAX_SIZE` | `kc.cache.redis.authz.local-lru.max-size` | Tamanho máximo do LRU | `1000` |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_TTL_SECONDS` | `kc.cache.redis.authz.local-lru.ttl-seconds` | TTL local por entry | `30` |

Detalhes: [Authorization](authorization.md).

## Exemplo mínimo

```bash
KC_CACHE=local
KC_COMMUNITY_REDIS_CACHE_ENABLED=true
KC_SPI_REDIS_CONNECTION_DEFAULT_MODE=standalone
KC_SPI_REDIS_CONNECTION_DEFAULT_NODES=redis:6379
KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX=kc
```

## Exemplo com authz e LRU local

```bash
KC_CACHE=local
KC_COMMUNITY_REDIS_CACHE_ENABLED=true
KC_SPI_REDIS_CONNECTION_DEFAULT_MODE=standalone
KC_SPI_REDIS_CONNECTION_DEFAULT_NODES=redis:6379
KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX=kc
KC_CACHE_REDIS_AUTHZ_ENABLED=true
KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_ENABLED=true
KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_MAX_SIZE=2000
KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_TTL_SECONDS=30
```
