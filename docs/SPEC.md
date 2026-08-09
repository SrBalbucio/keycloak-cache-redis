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
| `keyPrefix` | prefixo aplicado a todas as chaves Redis (`KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX` ou alias `KC_REDIS_KEY_PREFIX`) | _(vazio)_ |

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
5. **Fase 4:** `ClusterProvider` via Redis PUBSUB — multi-node na mesma região (seção 13).
6. **Fase 5:** robustez — modos `sentinel`/`cluster`, CAS por operação, métricas (seção 14).
7. **Fase 6:** prontidão para produção — testes completos, load, operação/failover, documentação, CI/CD (seção 15).

Cada fase só inicia após a anterior validada (smoke/integração). Decisões transversais registradas na seção 16.

## 12. Fora de escopo (versão básica + fases 4–6)

- **Multi-region active-active** — não haverá forwarding de eventos/invalidação entre regiões geográficas.
- **Migração de sessões existentes** (`importUserSessions` no-op) — após o switchover os usuários reautenticam; ver seção 14.4.
- **Persistência em banco de tokens revogados / sessões offline** — permanece somente em Redis.
- **Authorization Services** — adiado por decisão (seção 16.1); a extensão mantém o cache de authorization desativado.

## 13. Fase 4 — `ClusterProvider` via Redis PUBSUB (multi-node, single-region)

### 13.1 Objetivo

Permitir deployments multi-node na mesma região. Sem o `ClusterProvider`, cada nó opera isolado e as invalidações de caches locais (realm/user/client) não propagam entre nós. As sessões em si já ficam no Redis (fonte da verdade); o ClusterProvider atende à coordenação e invalidação interna do Keycloak.

### 13.2 Componentes (port da referência, Jedis → Lettuce)

- `RedisPubsubClusterProviderFactory` — id `infinispan` (sobrescreve o built-in), `order = PROVIDER_PRIORITY + 1`, `IsSupported`:
  - Duas conexões Lettuce dedicadas: `publisher` (`sync()`) e `subscriber` (`StatefulRedisPubSubConnection<String,String>`).
  - Registra `RedisPubSubListener<String,String>` no canal de eventos (ex.: `kc:cluster:events`).
  - `close()` fecha o `ClusterProvider` + subscriber + publisher.
- `RedisPubsubClusterProvider implements ClusterProvider`:
  - `notify(...)` → serializa a notificação e faz `publish` no canal.
  - `registerListener(...)` → listener local + multicast dos eventos recebidos via pub/sub (invalidação local).
  - `executeIfNotExecuted(eventKey, taskId, runnable)` → idempotência via `SET <key> NX EX <ttl>` (ex.: `kc:cluster:task:<eventKey>:<taskId>`).
  - `getClusterStartupTime()` → chave compartilhada `kc:cluster:startTime` (primeiro nó define com `SET NX`; os demais leem o valor existente).
- `ClusterEventSerializer` + mixins Jackson:
  - Wrapper de notificação (id, eventType, payload, endereços, ignoreSender).
  - Mixins para eventos de invalidação internos: `InvalidationEvent`, `UserFullInvalidationEvent`, `UserCacheRealmInvalidationEvent`, `RealmUpdatedEvent`, `ClientUpdatedEvent`, `RoleUpdatedEvent`, etc. (portar a lista da referência).
- Nota: classes internas do Keycloak exigem mixins para serialização JSON.

### 13.3 Lettuce — pontos de atenção

- `StatefulRedisPubSubConnection` é dedicada (não multiplexada com o client de comandos).
- Publisher: `connection.sync().publish(channel, json)`.
- `RedisPubSubListener` implementando `message(channel, payload)` → desserializa → invalidação local.
- Não usar a mesma conexão para comandos e subscribe (Lettuce bloqueia o subscribe na conexão de comandos).

### 13.4 Escopo e validação

- Single-region: apenas PUBSUB dentro da região; **sem** forwarding cross-region (SNS/GCP fora de escopo).
- Deploy de validação: docker-compose com 2+ nós Keycloak + Redis. Validar:
  - Logout/login em um nó reflete no outro (sessão revogada é fonte no Redis).
  - Atualização de realm/cliente propaga invalidação de cache local entre nós.
  - `executeIfNotExecuted` (tasks) sem duplicação.
- Sticky session: **não necessária** — sessões compartilhadas no Redis; o shim `DisabledStickySessionEncoderProvider` (Fase 1) já desativa o route.

## 14. Fase 5 — Robustez: sentinel/cluster, CAS por operação, métricas, migração

### 14.1 Modos `sentinel` e `cluster`

- **Sentinel:** `RedisURI.Builder.sentinels(nodes).sentinelMasterId(masterName)` (+ ssl, user/pass, timeout, database). Failover automático do Lettuce; fallback `eval` em NOSCRIPT já coberto (Fase 1).
- **Cluster:** `RedisClusterClient`. Impacto no `RedisChangelogTransaction`:
  - `MULTI/EXEC` não é válido entre slots → em modo cluster, executar `DEL`+`SREM` (delete) e `SADD` (índices) como comandos individuais, sem atomicidade (como na referência).
  - Tradeoff documentado: consistência eventual dos índices secundários em cluster; aceitável porque entidades de sessão são curtas e as leituras filtram por realm.
  - Evolução opcional: **hash tags** `{...}` para colocar entidade + índices no mesmo slot e preservar atomicidade via Lua.
- Validação: docker-compose com topologia de 3 nós Redis (cluster) e 2+ sentinels.

### 14.2 CAS por operação (corrige known issue da referência)

- Problema: o rebase field-level perde **operações lógicas** sob concorrência (ex.: `incrementFailures()` do loginFailure → contador recalculado de base obsoleta).
- Solução: estender `RedisHashCas`/`RedisChangelogTransaction` com **funções Lua por entidade** que executam a operação lógica atomicamente:
  - Contadores (`HINCRBY`) — ex.: loginFailure `incrementFailures`/`decrementFailures`.
  - Consumo/remoção atômica — singleUseObjects (`removeIfPresent`).
  - Update de `lastSessionRefresh`/`started` sem reescrever o hash inteiro.
- Referência de design: o `Updater` do Keycloak (sessões remote/infinispan) — replicar a semântica por operação, não por campo.
- Retry: manter retry CAS (máx. 3) para writes field-level; operações lógicas ficam dentro do script.

### 14.3 Métricas (Micrometer)

- Registrar no registry global do Keycloak (endpoint `/metrics`).
- **Lettuce nativo:** `MicrometerCommandLatencyRecorder` + `MicrometerOptions` (`io.lettuce.core.micrometer`) para latência por comando; habilitar na `DefaultRedisConnectionProviderFactory`.
- **Contadores por cache/operação** (port `RedisMetrics`): `HGETALL`, `HSETEX`, `HSET`, `SADD`, `HDEL`, `SREM`, `DEL`, `WATCH`; tags: `cache`, `operation`.
- Conexão: up/down, conexões ativas, failovers.

### 14.4 Migração de sessões (`importUserSessions`)

- Decisão: manter **no-op** no escopo básico. Sem migração Infinispan → Redis; após o switchover os usuários reautenticam.
- Documentar no README operacional (seção 15.4).
- Evolução futura (fora de escopo): job de import de sessões persistentes.

## 15. Fase 6 — Prontidão para produção

### 15.1 Testes completos

- Portar o testsuite de modelo da referência (`KeycloakModelTest`, `keycloak-quarkus-server` em test scope, H2, `keycloak.model.parameters`, `testcontainers-redis`) cobrindo as 4 regiões:
  - userSessions: create/get/remove, offline, remember-me, expiração, índices.
  - authenticationSessions: fluxo de login completo.
  - loginFailures: lockout, increment/decrement, reset.
  - singleUseObjects: consume, `removeIfPresent`, expiração.
- Cluster (Fase 4): teste de invalidação/integração entre 2 nós.
- Unit: `RedisHashCas` (Lua) e `MapEntity` (dirty/deleted, maps/sets).
- Investigar os testes "skipped/failing" da referência (semântica de transação única) e corrigir.

### 15.2 Benchmark / carga

- Load test (k6/JMeter): login/logout/refresh, throughput, latência P95, volume de sessões no Redis.
- Comparar com baseline Infinispan.
- Identificar gargalos: round-trips, pipelining, CAS retries.

### 15.3 Operação / failover

- Failover: matar Redis/sentinel master; validar reconexão e recuperação NOSCRIPT.
- Backup/restore: RDB/AOF; snapshot.
- Restart do Keycloak preservando sessões.
- Monitoramento: alertas de conexão Redis + métricas de operação (seção 14.3).

### 15.4 Documentação e deploy

- README operacional: tabela de propriedades, modos de conexão, multi-node, limitações (authz off, sem migração, sticky session).
- docker-compose de referência (2 nós + sentinel + cluster).
- Troubleshooting: NOSCRIPT, CAS retries, perda de conexão.

### 15.5 CI/CD

- GitHub Actions: build, unit tests, integration tests (testcontainers), publicação do jar `-withdeps`.
- Versionamento e releases.

### 15.6 Acompanhamento de upgrades do Keycloak

- Revalidar a cada release (26.x → 27.x): interfaces de sessão e `DatastoreProvider`.
- Roteiro de migração de versão no README.

## 16. Decisões registradas

### 16.1 Authorization — adiado

- O cache de authorization (cache-aside sobre JPA; default `InfinispanStoreFactory`) fica **desativado** via `NullCachedStoreProviderFactory` (id `default`), como na referência — o Keycloak usa `InfinispanStoreFactory` diretamente em vários pontos e a autorização "provavelmente não funciona" com a extensão ativa.
- **Não** será implementado um cache de authorization em Redis nesta versão: é ortogonal ao valor central (sessões), é cache-aside (não é fonte da verdade) e o problema multi-node é o mesmo ClusterProvider da Fase 4.
- Se houver demanda por Authorization Services: avaliar o caminho barato (rodar **sem cache**, direto no store JPA — requer validação do fallback no KC 26.7) antes de qualquer implementação em Redis.

### 16.2 ClusterProvider

- Necessário apenas em multi-node. Single-node funciona sem. A Fase 4 o introduz para single-region.

### 16.3 Sticky session

- Não necessária (sessões no Redis; shim desativa o route).

### 16.4 Migração de sessões

- No-op; documentada (seção 14.4).
