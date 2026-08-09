# Visão geral

## Propósito

O **keycloak-cache-redis** é uma extensão community para Keycloak que:

1. Substitui os providers de sessão baseados em Infinispan por armazenamento em **Redis** ou **Valkey** (cliente **Lettuce**).
2. Oferece **cache-aside** para Authorization Services sobre o store JPA.
3. Coordena vários nós Keycloak na mesma instalação via **Redis PUBSUB** (invalidação de caches locais).

A extensão só entra em vigor quando o feature flag está ligado e o Keycloak roda com `KC_CACHE=local` (sem Infinispan distribuído para essas regiões).

## Stack

| Item | Valor |
|------|--------|
| Keycloak alvo | 26.7.1 |
| Java | 17 |
| Cliente Redis | Lettuce 6.5.1 |
| Artefato | `keycloak-cache-redis-1.0-SNAPSHOT-withdeps.jar` |
| Group / artifact | `balbucio.keycloak.cache:keycloak-cache-redis` |

## O que está implementado

| Área | Status | Descrição |
|------|--------|-----------|
| User sessions (online/offline) | Implementado | HASH + índices SET + CAS Lua |
| Authenticated client sessions | Implementado | Ligadas às user sessions |
| Authentication sessions | Implementado | Root + tabs por sessão |
| Login failures | Implementado | Contadores de brute-force |
| Single-use objects | Implementado | Action tokens / revoked keys |
| ClusterProvider (PUBSUB) | Implementado | Invalidação entre nós no mesmo Redis |
| Sticky session desabilitada | Implementado | Sessões compartilhadas no Redis |
| Public key storage local | Implementado | `ConcurrentHashMap` por processo |
| Authorization cache-aside | Implementado | 5 stores + invalidação por geração |
| LRU local de authz | Implementado (opcional) | Desligado por padrão |
| Conexão standalone / sentinel / cluster | Implementado | SPI `redisConnection` |
| Métricas Micrometer | Implementado | Latência Lettuce + contadores |

## Arquitetura

```
Keycloak (KC_CACHE=local)
    │
    ├─ RedisDatastoreProvider (id=legacy)
    │     └─ roteia userSessions / authSessions / loginFailures / singleUseObjects
    │           para factories Redis (id=infinispan, order maior)
    │
    ├─ Stores de sessão
    │     Redis HASH + índices SET + Lua CAS
    │
    ├─ ClusterProvider
    │     Redis PUBSUB (canal cluster:events)
    │
    ├─ CachedStoreProvider (authz)
    │     cache-aside JSON sobre StoreFactory JPA
    │
    └─ RedisConnectionProvider (SPI redisConnection)
          Lettuce: standalone | sentinel | cluster
```

### Modelo de persistência das sessões

1. Entidades como Redis **HASH** (`MapEntity`), com campos `version` e opcionalmente `expiration`.
2. Mutações acumuladas em `RedisChangelogTransaction` (enlistadas após a TX do Keycloak).
3. Commit via **Lua CAS** (`RedisHashCas`) com retries.
4. Índices secundários como **SETs** (user, realm, client, broker, etc.).
5. Em modo Redis Cluster, `MULTI/EXEC` é desativado: atualizações de índice são não-atômicas (consistência eventual).

### Authorization

Caminho separado: leitura/escrita em Redis com TTL; o **JPA permanece a fonte da verdade**. Falhas de Redis são tratadas como cache miss (não-fatal).

## Pacotes principais

Raiz: `balbucio.keycloak.cache.redis`

| Pacote | Responsabilidade |
|--------|------------------|
| *(raiz)* | Datastore bridge, `MapEntity`, changelog, CAS, métricas |
| `common` | Feature flag, constantes, prefixo de chaves |
| `connection` | SPI de conexão Lettuce |
| `userSession` | User + client sessions |
| `authSession` | Authentication sessions |
| `loginFailure` | Login failures |
| `singleUseObject` | Single-use / revoked |
| `cluster` | ClusterProvider PUBSUB |
| `authz` | Cache de Authorization Services |
| `compatibility` | Sticky session off + public keys em memória |

## Providers SPI

As factories usam o mesmo `id` dos providers Infinispan de fábrica (`infinispan` / `legacy` / `default`) com `order` maior, para prevalecer quando o feature flag está ativo.

| Interface | Implementação | id | order |
|-----------|---------------|----|-------|
| `DatastoreProviderFactory` | `RedisDatastoreProviderFactory` | `legacy` | 2 |
| `UserSessionProviderFactory` | `RedisUserSessionProviderFactory` | `infinispan` | 2 |
| `AuthenticationSessionProviderFactory` | `RedisAuthenticationSessionProviderFactory` | `infinispan` | 2 |
| `UserLoginFailureProviderFactory` | `RedisUserLoginFailureProviderFactory` | `infinispan` | 2 |
| `SingleUseObjectProviderFactory` | `RedisSingleUseObjectProviderFactory` | `infinispan` | 2 |
| `ClusterProviderFactory` | `RedisPubsubClusterProviderFactory` | `infinispan` | 3 |
| `CachedStoreProviderFactory` | `RedisCachedStoreProviderFactory` | `default` | 2 |
| `StickySessionEncoderProviderFactory` | `DisabledStickySessionEncoderProvider` | `infinispan` | 2 |
| `PublicKeyStorageProviderFactory` | `MapPublicKeyStorageProviderFactory` | `infinispan` | 2 |
| `RedisConnectionProviderFactory` | `DefaultRedisConnectionProviderFactory` | `default` | — |

Registro via `@AutoService` → `META-INF/services` gerado no compile.

## Próximos passos de leitura

1. [Instalação](installation.md) — colocar a extensão para rodar
2. [Configuração](configuration.md) — variáveis necessárias
3. [Sessões](sessions.md) / [Authorization](authorization.md) — detalhes de cada feature
