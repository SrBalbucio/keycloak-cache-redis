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

### Adapter snapshot + write-through

O `RedisAuthenticationSessionAdapter` é **snapshot + write-through** (não uma view viva do
root entity). Motivo: no login browser, o core remove a tab e **na mesma request** lê
`getProtocol()` (`AuthenticationManager.redirectAfterSuccessfulFlow:943`, após
`AuthenticationSessionManager.removeTabIdInAuthenticationSession:240` apagar todos os campos
`t.<tabId>.*`, incluindo `protocol`). Uma view viva devolveria `null` → NPE em
`DefaultKeycloakSession.getProvider:194` (`List.of(name, null)`). O stock
(`AuthenticationSessionAdapter` do Infinispan) segura entity desacoplada e sobrevive à
remoção — o nosso adapter faz o mesmo:

- `fields` capturado no constructor via `parent.getTabMap(tabId, "")`
- getters leem do snapshot; setters atualizam snapshot **e** parent (persistência)
- testes de regressão em `RedisAuthenticationSessionProviderIntegrationTest`:
  `removedTabAdapterStillExposesFields`, `writesBeforeTabRemovalArePersisted`

Residual: mutações externas à tab feitas direto no root (`restartSession`, eviction) não se
refletem num adapter já construído — mesmo comportamento do stock, fora do fluxo normal.

### Contratos dos adapters (auditoria)

Verificação de quais adapters são "live views" sobre entity compartilhada e se o core lê
campos após remoção na mesma request:

| Adapter | Estrutura | Leitura após remoção | Risco |
|---|---|---|---|
| `RedisAuthenticationSessionAdapter` | **era live view** → agora snapshot | `redirectAfterSuccessfulFlow` lê `getProtocol()` pós-remoção (garantido pelo core) | **Corrigido** (acima) |
| `RedisRootAuthenticationSessionAdapter` | `extends MapEntity` (container das tabs) | n/a (é o próprio root) | Nenhum |
| `RedisUserSessionAdapter` | `extends MapEntity` (own data) + `check()` | possível em logout/backchannel | Latente (abaixo) |
| `RedisAuthenticatedClientSessionAdapter` | `extends MapEntity` (own data) + `check()` | possível após `detachFromUserSession` | Latente (abaixo) |
| `RedisUserLoginFailureAdapter` | `extends MapEntity` (own data), sem `check()` | raro (failures não são lidas pós-remoção) | Baixo |

Os adapters de user/client/loginFailure seguram a própria `MapEntity` — **não** têm o bug do
null silencioso. Risco residual (não agudo): `check()` lança `ModelIllegalStateException` se
a entity foi marcada para deleção; se o core ler o adapter após o provider marcar deleção
(logout backchannel, `detachFromUserSession`), lança exceção em vez de servir stale. É
fail-fast, mas o follow-up é um teste de regressão desses fluxos, alinhando ao stock (entity
desacoplada sem throw) se necessário — ver [Testes de modos de falha](testing-failure-modes.md).

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
