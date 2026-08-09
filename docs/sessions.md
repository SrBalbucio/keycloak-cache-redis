# Sessões e objetos efêmeros

As regiões de sessão do Keycloak passam a usar Redis como fonte da verdade. Todas as chaves abaixo assumem prefixo configurado (ex.: `kc:`).

## User sessions

Provider: `RedisUserSessionProvider`

- Sessões de usuário **online** e **offline**
- Client sessions autenticadas ligadas à user session
- Índices SET: user, realm, client, broker session/user, corresponding session

### Chaves

| Tipo | Padrão |
|------|--------|
| User session | `user-session:<id>` / `user-session-offline:<id>` |
| Client session | `authenticated-client:<compound>` / `authenticated-client-offline:<compound>` |
| Índices | `user-session*:user-index:…`, `realm-index:…`, `client-index:…`, etc. |

### Persistência

- Entidade: Redis HASH com `version` e dirty-tracking (`MapEntity`)
- Commit: Lua CAS (`RedisHashCas`) via `RedisChangelogTransaction`
- Offline: vive no Redis (não há preload do banco)

### Migração

`importUserSessions` / `loadPersistentSessions` são **no-ops**. Não há migração Infinispan → Redis: após o switchover, os usuários reautenticam.

## Authentication sessions

Provider: `RedisAuthenticationSessionProvider`

- Root authentication session + tabs filhos no mesmo hash
- Índice por realm para cleanup
- Limite de tabs: `authSessionsLimit` (default **300**)

| Tipo | Padrão |
|------|--------|
| Auth session | `auth-session:<id>` |
| Índice | `auth-session:realm-index:<realmId>` |

## Login failures

Provider: `RedisUserLoginFailureProvider`

- Contadores de falha por realm/usuário (brute-force)
- Cleanup no evento `UserModel.UserRemovedEvent`

| Tipo | Padrão |
|------|--------|
| Registro | `login-failure:<realmId>:<userId>` |
| Índice | `login-failure:realm-index:<realmId>` |

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
    → commit: Lua CAS no HASH + atualização de índices SET
    → retry em conflito de versão
```

## Classes principais

| Classe | Papel |
|--------|-------|
| `MapEntity` | Entidade HASH com versionamento |
| `RedisChangelogTransaction` | Unit-of-work + índices + CAS |
| `RedisHashCas` | Script Lua de concorrência otimista |
| `RedisUserSessionProvider` (+ adapters/keys/indexes) | User/client sessions |
| `RedisAuthenticationSessionProvider` | Auth sessions |
| `RedisUserLoginFailureProvider` | Login failures |
| `RedisSingleUseObjectProvider` | Single-use |
