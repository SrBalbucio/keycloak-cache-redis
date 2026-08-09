# Especificação — Suporte a Cache Redis via Extensão para Keycloak

Versão: 1.0 (básica, single-region)
Status: Aprovada

## 1. Contexto e decisões

O Keycloak usa **Infinispan** para os caches distribuídos de sessão, que é operacionalmente complexo (JGroups, cluster externo, split-brain, upgrades). O objetivo é substituir esses caches por **Redis** através de uma extensão (jar em `providers/`), sem modificar o núcleo do Keycloak.

**Abordagem:** implementar o SPI `DatastoreProvider` (Keycloak ≥ 26), sobrescrevendo as 4 regiões de sessão, e combinar com `KC_CACHE=local` (desliga o Infinispan distribuído). O Redis passa a ser a fonte da verdade das sessões.

**Decisões acordadas:**

| Item | Decisão |
|---|---|
| Escopo | **Uma região geográfica** — implementar as 4 regiões de sessão, mas só topologia single-region (sem multi-region active-active) |
| Região inicial | **userSessions** (entrega em fases) |
| Cliente Redis | **Lettuce** (`lettuce-core`) |
| Keycloak alvo | **26.7.1** |
| Java | 17 (pom atual, suportado pelo KC 26) — subir para 21 só se necessário |
| Referência de arquitetura | `p2-inc/keycloak-redis-cache` (código usa Jedis; portaremos o design para Lettuce) |

## 2. Arquitetura

```
Feature toggle ── KC_COMMUNITY_REDIS_CACHE_ENABLED (env) / kc.community.redis.cache.enabled (prop)
      │  gate via IsSupported (EnvironmentDependentProviderFactory)
      ▼
SPI redisConnection (internal) ── RedisConnectionProvider (Lettuce: sync/async/pubsub)
      │
      ▼
RedisDatastoreProviderFactory (id=legacy, order=PRIORITY+1)
      │  sobrescreve DatastoreProvider → delega as 4 regiões para session.getProvider(...)
      ▼
Infra de storage: Key · MapEntity (hash+version+dirty) · RedisHashCas (Lua) ·
                  RedisChangelogTransaction (unit-of-work por entidade)
      │
      ▼
Regiões (factories id="infinispan" p/ sobrescrever built-ins):
  userSessions (F1) · authenticationSessions (F2) · loginFailures (F3) · singleUseObjects (F3)
      │
      ▼
Shims de compatibilidade: NullCachedStoreProviderFactory · DisabledStickySessionEncoderProvider
                          MapPublicKeyStorageProvider · (ClusterProvider = fase futura)
```

## 3. Feature toggle e configuração

- `CommunityProfiles`: lê `KC_COMMUNITY_REDIS_CACHE_ENABLED` e `kc.community.redis.cache.enabled`. Todos os factories implementam `IsSupported` para só carregar quando ativo.
- Deploy exige também `KC_CACHE=local`.

Config do SPI `redisConnection` (provider `default` — prefixo env `KC_SPI_REDIS_CONNECTION_DEFAULT_*`):

| Propriedade | Descrição | Default |
|---|---|---|
| `mode` | `standalone` \| `sentinel` \| `cluster` | `standalone` |
| `nodes` | hosts `host:port` separados por vírgula | `redis:6379` |
| `masterName` | modo sentinel (obrigatório nesse modo) | — |
| `ssl` | bool | `false` |
| `username` / `password` | autenticação | — |
| `timeout` | ex.: `2000`, `2s`, `500ms` | `2000ms` |
| `database` | índice lógico do Redis | `0` |

MVP suporta **standalone**; `sentinel`/`cluster` ficam previstos no design, mas marcados como fase futura.

## 4. SPI de conexão Redis (Lettuce)

- `RedisConnectionSpi` — `name="redisConnection"`, `internal=true`, registrado via `@AutoService(Spi.class)`.
- `RedisConnectionProvider`:
  - `RedisCommands<String,String> sync()` — comandos síncronos
  - `RedisAsyncCommands<String,String> async()` — para pipelining/bulk
  - `StatefulRedisPubSubConnection<String,String> pubSub()` — reservado para ClusterProvider
  - `RedisMode mode()`
- `DefaultRedisConnectionProviderFactory` (id `default`, `IsSupported`):
  - `init(Config.Scope)`: monta `RedisURI.Builder` (host/port, SSL, user/pass, timeout, database), cria `RedisClient` (standalone/sentinel) ou `RedisClusterClient` (cluster).
  - Client único e compartilhado (Lettuce é multiplexado/thread-safe); `close()` no shutdown da factory.
  - Faz `scriptLoad` do script Lua do CAS uma vez no init.

**Decisão de build (importante — Lettuce ≠ Jedis):** Lettuce puxa **Netty** e **Reactor**, que o Keycloak/Quarkus 26.7.1 já embute (Quarkus 3.27.3). Recomendação: marcar `netty-*` e `reactor-core` como `provided` e empacotar só `lettuce-core` (+`reactive-streams` se necessário) no jar `-withdeps`. Evitar shade com relocation do Netty (conflito com o do Quarkus). Escolher versão do `lettuce-core` cuja exigência de Netty seja compatível com a embutida no KC 26.7.1.

## 5. Camada de storage (port Jedis → Lettuce)

### 5.1 `Key`

```java
public interface Key {
  String key(); // ex.: "user-session:<id>"
}
```

### 5.2 `MapEntity`

Port direto (é Java puro): `Map<String,String>` = hash do Redis; tracking de campos **dirty/deleted**; `getMap(prefix)`/`getSet(prefix)` (sub-campos: notes, client sessions); sentinela de null; campo `version` para CAS; `markForDelete`. Substituir Guava por JDK (ou usar a Guava já presente no classpath do KC).

### 5.3 `RedisHashCas` (script Lua)

Script idêntico ao da referência: `HGET version` → CAS → `HSET`/`HDEL` → `HINCRBY version` → `PEXPIREAT`; retorna `1` (ok), `0` (mismatch), `-1` (conflito create), `-2` (args inválidos). Port para Lettuce: `scriptLoad`/`evalsha` (`RedisScriptingCommands`) com fallback para `eval` em **NOSCRIPT** (failover de sentinel). Funciona dentro de `multi()/exec()` e fora.

### 5.4 `RedisChangelogTransaction`

Unit-of-work por entidade (`Map<K,A> cache` + `Map<K,A> toDelete`), herda `AbstractKeycloakTransaction` e registrado no `TransactionManager` via `enlistAfterCompletion`.

- **Leitura:** `get`/`getIfPresent`/`getAll` via `HGETALL`, com lazy-expiration check.
- **Commit:**
  - Deletes → `DEL` + `SREM` de índices em `MULTI/EXEC`.
  - Dirty → `hsetex` (CAS) com retry (máx. 3) e **rebase field-level** (replay de mudanças pendentes sobre dados recarregados).
  - Índices → `SADD` em transação.
- **Pipelining/bulk** com Lettuce: `setAutoFlushCommands(false)` + `AsyncCommands` + `flushCommands()` + await futures (substitui o pipeline do Jedis).
- Rollback: no-op (como na referência).

## 6. Override do DatastoreProvider

- `RedisDatastoreProviderFactory` — id `legacy`, estende `DefaultDatastoreProviderFactory`, `order = PROVIDER_PRIORITY + 1`, `@AutoService(DatastoreProviderFactory.class)`.
- `RedisDatastoreProvider extends DefaultDatastoreProvider` sobrescreve:
  - `userSessions()` → `session.getProvider(UserSessionProvider.class)`
  - `authSessions()` → `session.getProvider(AuthenticationSessionProvider.class)`
  - `loginFailures()` → `session.getProvider(UserLoginFailureProvider.class)`
  - `singleUseObjects()` → `session.getProvider(SingleUseObjectProvider.class)`

Isso faz o Keycloak resolver essas regiões para os nossos providers (que sobrescrevem os built-ins por nome `infinispan` + ordem).

## 7. Regiões

### 7.1 Fase 1 — userSessions (prioridade)

- `RedisUserSessionProviderFactory` — id `infinispan`, `order=PRIORITY+1`, `@AutoService(UserSessionProviderFactory.class)`, `IsSupported`, `loadPersistentSessions` no-op.
- `RedisUserSessionProvider implements UserSessionProvider`:
  - Duas `RedisChangelogTransaction`: `userSession` e `clientSession` (client sessions aninhadas, id `<userSessionId>::<clientId>`).
  - Online + offline (`createOfflineUserSession`, `createOfflineClientSession`, `getOfflineUserSessionsStream`), remember-me, transient (→ delete no commit), expiração via `ExpirableEntity`.
  - `importUserSessions` = no-op no MVP (sem migração de sessões).
- Índices secundários (Redis **Sets**):
  - `user-session:user-index:<userId>`
  - `user-session:realm-index:<realmId>`
  - `user-session:broker-session-index:<brokerSessionId>`
  - `user-session:broker-user-index:<brokerUserId>`
  - `user-session:corresponding-session-index:<id>`
  - `authenticated-client:client-index:<clientId>`
- Adapters: `RedisUserSessionAdapter`/`RedisAuthenticatedClientSessionAdapter extends MapEntity`.

### 7.2 Fase 2 — authenticationSessions

- `RedisAuthenticationSessionProvider` + `RedisRootAuthenticationSessionAdapter`; auth notes via `getMap`; índices por auth session/root auth session.

### 7.3 Fase 3 — loginFailures

- `RedisUserLoginFailureProvider`; chave `login-failure:<realm>:<userId>`; campos simples + expiração (lockout).

### 7.4 Fase 3 — singleUseObjects

- `RedisSingleUseObjectProvider`; chave `single-use:<prefix>:<id>`; expiração e `removeIfPresent` **atômico** (relevante: CVE de isolamento corrigida no 26.7.1).

## 8. Shims de compatibilidade

| Shims | id | Objetivo |
|---|---|---|
| `NullCachedStoreProviderFactory` | `default` | Desativa o cache de authorization (depende de Infinispan). Authorization fora do escopo básico |
| `DisabledStickySessionEncoderProvider` | `infinispan` | Sessões compartilhadas no Redis → não anexar route |
| `MapPublicKeyStorageProvider` (+factory) | `infinispan` | Substitui storage de chaves públicas baseado em Infinispan por mapa em memória |

Todos com `IsSupported`. (`NullInfinispanConnectionProviderFactory` ficou comentado na referência — não portar.)

## 9. Build e empacotamento

- `pom.xml`: adicionar deps `keycloak-server-spi`, `keycloak-server-spi-private`, `keycloak-model-storage`, `keycloak-model-storage-private`, `keycloak-model-infinispan`, `keycloak-model-jpa`, `keycloak-common`, `keycloak-core`, `keycloak-services` (**26.7.1**) + `io.lettuce:lettuce-core`. Anotações: `auto-service`; Lombok opcional.
- `maven-shade-plugin` → `keycloak-cache-redis-<versão>-withdeps.jar` (lettuce-core + deps não-provided), instalável em `providers/`.
- `@AutoService` gera `META-INF/services/*` automaticamente.
- Remover o `Main.java` inicial (starter do template).

## 10. Testes

- **Unit:** `MapEntity` (dirty/deleted, maps/sets) e `RedisHashCas` (Lua contra Redis real via Testcontainers).
- **Integração/model:** portar o padrão do testsuite da referência (`KeycloakModelTest`, `keycloak-quarkus-server` em test scope, H2, `keycloak.model.parameters`, `testcontainers-redis`).
- **Smoke:** `docker-compose.yml` local (Keycloak 26.7.1 + Redis/Valkey) — validar login/logout, refresh token, sessões offline, sessões no Admin Console, e persistência de sessão após restart do Keycloak.

## 11. Entrega em fases

1. **Fase 0 — esqueleto:** pom (deps KC 26.7.1, Java 17), package `balbucio.keycloak.cache.redis`, AutoService, `CommunityProfiles`/`IsSupported`, remoção do `Main.java`.
2. **Fase 1 — MVP:** SPI `redisConnection` (standalone/Lettuce) + `Key`/`MapEntity`/`RedisHashCas`/`RedisChangelogTransaction` + `RedisDatastoreProvider` + região **userSessions** + shims + docker-compose smoke.
3. **Fase 2:** região **authenticationSessions**.
4. **Fase 3:** regiões **loginFailures** + **singleUseObjects**.
5. **Fase 4 (futuro/opcional):** `ClusterProvider` (PUBSUB, multi-node single-region), modos `sentinel`/`cluster`, métricas, testes completos.

## 12. Fora de escopo (versão básica)

- Multi-region active-active.
- Migração de sessões existentes.
- Persistência em banco de tokens revogados/offline.
- Authorization (feature desligada ou cache nullado).
- `ClusterProvider` (PUBSUB) — **documentar impacto**: single-node funciona sem; multi-node na mesma região pode precisar dele para invalidação/coordenação interna do Keycloak → decidir na Fase 4.
