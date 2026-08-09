# Authorization Services (cache Redis)

Cache-aside sobre o store JPA das Authorization Services. O banco continua sendo a fonte da verdade; o Redis acelera leituras frequentes.

Quando `KC_CACHE_REDIS_AUTHZ_ENABLED=false`, o provider delega direto ao JPA (sem cache).

## Escopo cacheado

| Store | Lookups cacheados |
|-------|-------------------|
| Resource | `findById`, `findByName` |
| ResourceServer | `findById`, `findByClient` |
| Scope | `findById`, `findByName` |
| Policy | `findById`, `findByResource` |
| PermissionTicket | `findById` (TTL mais curto) |

## Modelo de dados

- Snapshots `Cached*` serializados em JSON dentro de `CachedEntityEnvelope`
- Envelope inclui a **geração** do resource server no momento do put
- Leitura valida a geração atual (`authz:rs-gen:<resourceServerId>`); geração diferente → miss

### Chaves (relativas ao prefixo global)

| Uso | Padrão |
|-----|--------|
| Resource por id | `authz:resource:<id>` |
| Resource por nome | `authz:resource:<rsId>:name:<name>` |
| Scope por id | `authz:scope:<id>` |
| Scope por nome | `authz:scope:<rsId>:name:<name>` |
| Policy por id | `authz:policy:<id>` |
| Policy por resource | `authz:policy:<rsId>:resource:<resourceId>` |
| Resource server por id | `authz:resource-server:<id>` |
| Resource server por client | `authz:resource-server:client:<clientId>` |
| Permission ticket | `authz:permission-ticket:<id>` |
| Geração | `authz:rs-gen:<resourceServerId>` |

## Invalidação

Invalidação por **geração** (`INCR` na chave `authz:rs-gen:<rsId>`):

1. **Pré-write** — bump imediato antes da mutação (entries antigas ficam inválidas).
2. **Pós-commit** — segundo bump após a TX Keycloak (cobre race em que um reader repopula entre o primeiro bump e o commit).

Em falha de Redis, a operação de cache é ignorada (não-fatal); a request segue pelo JPA.

## LRU local (opcional)

Desligado por padrão. Quando habilitado:

- cache em memória por nó (`LocalAuthorizationCache`)
- invalidação cross-node via PUBSUB no canal `authz:invalidation`
- configurável por tamanho máximo e TTL curto (default 30s)

Útil para reduzir round-trips Redis em hot paths, com stale window limitado pelo TTL local e pelo PUBSUB.

## Configuração

Ver tabela completa em [Configuração](configuration.md). Resumo:

| Variável | Default |
|----------|---------|
| `KC_CACHE_REDIS_AUTHZ_ENABLED` | `true` |
| `KC_CACHE_REDIS_AUTHZ_TTL_SECONDS` | `1800` |
| `KC_CACHE_REDIS_AUTHZ_PERMISSION_TICKET_TTL_SECONDS` | `300` |
| `KC_CACHE_REDIS_AUTHZ_GEN_TTL_SECONDS` | `604800` |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_ENABLED` | `false` |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_MAX_SIZE` | `1000` |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_TTL_SECONDS` | `30` |

## Classes principais

| Classe | Papel |
|--------|-------|
| `RedisCachedStoreProviderFactory` | Factory SPI (`CachedStoreProvider`) |
| `RedisCachedStoreFactoryProvider` | Encapsula stores cacheadas |
| `RedisAuthorizationCache` | get / put / invalidate no Redis |
| `LocalAuthorizationCache` | LRU opcional + PUBSUB |
| `AuthorizationCacheConfig` | Leitura de env/properties |
| `AuthorizationCacheKey` | Layout de chaves |
| `AuthorizationInvalidation` | Double-INCR pré-write / pós-commit |
| `CachedEntityEnvelope` + `Cached*` | Snapshots serializáveis |
| `RedisCached*Store` / `*Adapter` | Stores e adapters por entidade |

## Métricas

Operações de authz usam tags `cache=authz` e `cache=authzGen` nos contadores `vendor.lettuce.cache`. Veja [Métricas](metrics.md).
