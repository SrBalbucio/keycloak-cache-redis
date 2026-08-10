# Sessões e objetos efêmeros

As regiões de sessão do Keycloak passam a usar Redis como fonte da verdade. Todas as chaves abaixo assumem prefixo configurado (ex.: `kc:`).

## User sessions

Provider: `RedisUserSessionProvider`

- Sessões de usuário **online** e **offline**
- Client sessions autenticadas ligadas à user session
- Índices SET (membership) + ZSET por `lastSessionRefresh` (paginação admin)
- Contadores Redis para `getActiveClientSessionStats`

### Chaves (hash-tag `{realmId}`)

| Tipo | Padrão |
|------|--------|
| User session | `{realmId}:user-session:<id>` / `{realmId}:user-session-offline:<id>` |
| Client session | `{realmId}:authenticated-client:<compound>` / offline twin |
| SET indexes | `{realmId}:user-session:realm-index`, `user-index:…`, `client-index:…`, broker/corresponding |
| ZSET indexes | `{realmId}:user-session:realm-z`, `{realmId}:user-session:client-z:<clientId>` |
| Stats | `{realmId}:user-session:client-stats:<clientId>` + `client-stats-index` |

Breaking: layout anterior sem hash-tag não é lido. Flush Redis (ou reauth) após upgrade.

### Persistência

- Entidade: Redis HASH com `version` e dirty-tracking (`MapEntity`)
- Commit: Lua CAS (`RedisHashCas`) + SADD/SREM/ZADD/ZREM via `RedisChangelogTransaction`
- Offline: Redis por padrão. Com SPI `persistOfflineSessions=true` (`KC_SPI_USER_SESSIONS_INFINISPAN_PERSIST_OFFLINE_SESSIONS`), write-through para `UserSessionPersisterProvider` e preload em `loadPersistentSessions` (marker `user-session:offline:loaded`).

### Admin / stats

- Paginação `getUserSessionsStream(realm, client, first, max)` usa `ZREVRANGE` no client ZSET (mais recente primeiro).
- `getActiveClientSessionStats` lê contadores (não hidrata todas as sessões).

### Migração

`importUserSessions` continua no-op (sem migração Infinispan → Redis). Offline JPA preload só com `persistOfflineSessions`.

## Authentication sessions

Provider: `RedisAuthenticationSessionProvider`

- Root authentication session + tabs filhos no mesmo hash
- Índice por realm para cleanup
- Limite de tabs: `authSessionsLimit` (default **300**)

| Tipo | Padrão |
|------|--------|
| Auth session | `{realmId}:auth-session:<id>` |
| Índice | `{realmId}:auth-session:realm-index` |

## Login failures

Provider: `RedisUserLoginFailureProvider`

- Contadores de falha por realm/usuário (brute-force)
- Cleanup no evento `UserModel.UserRemovedEvent`

| Tipo | Padrão |
|------|--------|
| Registro | `{<realmId>}:login-failure:<userId>` |
| Índice | `{<realmId>}:login-failure:realm-index` |

Hash-tags por realm permitem CAS + índices atômicos também em Redis Cluster.

## Single-use objects

Provider: `RedisSingleUseObjectProvider`

- Action tokens, revoked keys e afins
- Escrita imediata no Redis (não passa pelo changelog diferido)
- `remove` e `putIfAbsent` atômicos via Lua
- Notes em campos `n.*`; restrições de revoked tokens preservadas

| Tipo | Padrão |
|------|--------|
| Objeto | `single-use:<id>` |

## Fluxo de escrita (sessões com changelog)

```
Mutação no adapter
    → MapEntity marca campos dirty
    → RedisChangelogTransaction enlista na TX Keycloak
    → commit: Lua CAS no HASH + índices SET/ZSET
    → retry em conflito de versão
```
