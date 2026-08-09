# Especificação — Authorization Services sobre Redis

Versão: 1.1 (implementação A0-A4 concluída)
Status: Em implementação (A5 em andamento)
Baseline: `docs/SPEC.md` (v1.0, single-region) + código atual (4 regiões de sessão + `ClusterProvider` PUBSUB + métricas + modos `standalone`/`sentinel`/`cluster`)
Revisão: seção 16.1 do `SPEC.md` (Authorization — adiado)

## 1. Contexto e motivação

O `SPEC.md` (seção 16.1) adiou deliberadamente o suporte a Authorization Services:

> O cache de authorization (cache-aside sobre JPA; default `InfinispanStoreFactory`) fica **desativado** via `NullCachedStoreProviderFactory` (id `default`) […] **Não** será implementado um cache de authorization em Redis nesta versão: é ortogonal ao valor central (sessões), é cache-aside (não é fonte da verdade) e o problema multi-node é o mesmo ClusterProvider da Fase 4.

Na implementação atual (`compatibility/NullCachedStoreProviderFactory`), o `CachedStoreFactoryProvider` delega diretamente ao `StoreFactory` JPA, sem cache algum. **Toda avaliação de policy (decisão de autorização) hitting o banco** — visível em:

- `Resource.findById(id)` em cada `AuthorizationProvider.setContext(...)` / verificação de permissão.
- `Policy.findById(id)` em cada avaliação de policy durante a decisão.
- `Scope.findById(id)`, `ResourceServer.findByClient(client)`.

Em workloads com UMA / policy enforcement ativo (filtros de Polaro, gateways, resource servers protegidos), isso gera N+1 queries de JPA por requisição protegida.

**Esta especificação propõe uma camada de cache-aside em Redis** para as 5 stores de authorization, reusing a infraestrutura já existente da extensão (`RedisConnectionProvider`/Lettuce, `RedisKeySpace`, `RedisMetrics`, `ClusterProvider` PUBSUB), **sem mudar a fonte da verdade** (JPA permanece o store persistente de authz).

### 1.1 Decisões acordadas

| Item | Decisão |
|---|---|
| Semântica | **Cache-aside** (read-through com lazy load; write-through com invalidação). JPA continua fonte da verdade. |
| Unidade de cache | Entidades de authz serializadas como **String (JSON)** — **não** `MapEntity`/hash. Authz é read-mostly; CAS field-level é desnecessário. |
| Escopo de invalidação | **Por Resource Server** (geração/version). Bulk-invalidate sem rastrear chaves individuais. |
| Cross-node | Cache é **compartilhado** no Redis (um `DEL` é visível a todos os nós) — **PUBSUB não é necessário** para a camada core. |
| Layer local (opcional) | LRU em memória por nó (Fase A4), invalidada via PUBSUB em canal dedicado `kc:authz:invalidation`. |
| Stores cacheadas | `ResourceStore`, `ResourceServerStore`, `ScopeStore`, `PolicyStore` (by-id/by-unique-key). `PermissionTicketStore` só by-id. |
| Queries (find/filter) | **Não cacheadas** — vão direto ao JPA (paginadas, dinâmicas, baixa frequência). Equivalente ao comportamento Infinispan. |
| TTL | Default configurável (ex.: 30 min) como safety net contra stale reads; invalidação explícita é a via primária. |
| Feature toggle | Mesmo gate da extensão (`KC_COMMUNITY_REDIS_CACHE_ENABLED`) + sub-flag `kc.cache.redis.authz.enabled` (default `true` quando extensão ativa). |

### 1.2 O que NÃO muda

- O `StoreFactory` JPA permanece o store persistente; a extensão apenas intercepta `CachedStoreFactoryProvider`.
- Não há migração de dados; o cache é frio no boot e se aquece sob demanda.
- Authorization Services continua operacional com a extensão ativa (hoje via `NullCachedStoreProviderFactory`); esta spec apenas **acelera** sem mudar contrato.

## 2. Estado atual (antes desta spec)

```
CachedStoreProviderFactory (id="default")
  └─ NullCachedStoreProviderFactory
       └─ DelegatingCachedStore
            └─ session.getProvider(StoreFactory.class)   ← JPA direto, zero cache
                 ├─ JPAResourceStore
                 ├─ JPAResourceServerStore
                 ├─ JPAScopeStore
                 ├─ JPAPolicyStore
                 └─ JPAPermissionTicketStore
```

Resultado: `getResourceStore().findById(...)` → query JPA a cada chamada.

## 3. Arquitetura proposta

```
CachedStoreProviderFactory (id="default", order=PROVIDER_PRIORITY)   ← substitui NullCachedStoreProviderFactory
  └─ RedisCachedStoreProviderFactory  (IsSupported)
       └─ create(session) → RedisCachedStoreFactoryProvider
            ├─ ResourceStore      → RedisCachedResourceStore(JPA delegate + RedisAuthorizationCache)
            ├─ ResourceServerStore→ RedisCachedResourceServerStore(...)
            ├─ ScopeStore         → RedisCachedScopeStore(...)
            ├─ PolicyStore        → RedisCachedPolicyStore(...)
            └─ PermissionTicketStore → RedisCachedPermissionTicketStore(...)

RedisAuthorizationCache  (usa RedisConnectionProvider.sync() + Jackson)
  ├─ get(key, gen)        → GET (String JSON) + check generation
  ├─ put(key, gen, obj)   → SET EX ttl (String JSON)
  └─ invalidate(rsId)     → INCR rs-gen:<rsId>   (bulk invalidação barata)
```

### 3.1 Princípio cache-aside

| Operação | Fluxo |
|---|---|
| **Read** (`findById`) | `cache.get(key)` → hit (e `gen` válida) → desserializa → retorna adapter imutável.<br>Miss → `delegate.findById()` (JPA) → `cache.put(key, gen, obj)` → retorna. |
| **Write** (`create`/`save`/`delete`) | Invalidação **antes** da escrita (`cache.invalidate(rsId)` → INCR gen) → executa no delegate JPA → invalidação **após commit** (re-INCR gen, para cobrir leituras concorrentes que repovoaram entre a invalidação pré-escrita e o commit). |
| **Query** (`findByResourceServer`, `findByOwner`, paginadas) | **Direto ao delegate JPA**, sem cache. |

### 3.2 Por que String/JSON e não `MapEntity`/hash

As 4 regiões de sessão usam `MapEntity` (Redis hash) + `RedisHashCas` (CAS Lua) porque **sessões são fonte da verdade no Redis**, com updates field-level concorrentes (ex.: `lastSessionRefresh`, `notes`).

Authorization é **read-mostly cache-aside**: a entidade é carregada inteira do JPA, cached como snapshot, e invalidada por completo em caso de escrita. Não há updates incrementais no cache. Logo:

- `GET`/`SET` de uma String JSON é 1 round-trip (vs. `HGETALL` + parse field-by-field).
- Sem `RedisChangelogTransaction`, sem dirty-tracking, sem rebase — simplificação significativa.
- Serialização via Jackson (`ObjectMapper` já no classpath do Keycloak).

## 4. Estrutura do cache (keyspace)

Todo prefixado por `RedisKeySpace.prefix()` (ex.: `kc:`) como as demais regiões.

### 4.1 Chaves de entidade (cache-aside)

| Chave Redis | Value | TTL |
|---|---|---|
| `kc:authz:resource:<id>` | JSON `CachedResource` | `authz.cache.ttl-seconds` |
| `kc:authz:resource:<rsId>:name:<name>` | JSON `CachedResource` | idem |
| `kc:authz:scope:<id>` | JSON `CachedScope` | idem |
| `kc:authz:scope:<rsId>:name:<name>` | JSON `CachedScope` | idem |
| `kc:authz:policy:<id>` | JSON `CachedPolicy` | idem |
| `kc:authz:policy:<rsId>:type:<type>:resource:<resId>` | JSON `CachedPolicy` | idem (lookup por policy de recurso) |
| `kc:authz:resource-server:<id>` | JSON `CachedResourceServer` | idem |
| `kc:authz:resource-server:client:<clientId>` | JSON `CachedResourceServer` | idem |
| `kc:authz:permission-ticket:<id>` | JSON `CachedPermissionTicket` | idem |

> **Nota:** nomes de policies/scopes/resources são únicos **por Resource Server** (não globalmente), por isso as chaves by-name incluem `<rsId>`.

### 4.2 Chave de geração (invalidação bulk)

| Chave Redis | Value | Operação |
|---|---|---|
| `kc:authz:rs-gen:<rsId>` | inteiro (geração) | `INCR` em toda escrita; `GET` em toda leitura de cache |

**Mecanismo:** cada entry cached carrega o campo `_gen` (a geração vigente no momento do `put`). No `get`, se `cacheEntry._gen < currentGen` → trata como miss (stale) e recarrega do JPA. Um único `INCR` invalida **todas** as entries daquele resource server — sem rastrear chaves individuais.

> A geração **não expira** (ou tem TTL longo, ex.: 7 dias, com `SET NX` no primeiro uso). É a âncora de consistência.

### 4.3 Adapters imutáveis

Cada `Cached*` (ex.: `CachedResource`) é um **POJO imutável** contendo os campos serializáveis da entidade (id, name, displayName, type, uris, iconUri, resourceServerId, owner, scopes, attributes). O adapter exposto ao Keycloak (`ResourceAdapter`) lê do `Cached*` e, para operações de escrita, **invalida o cache** e delega ao `StoreFactory` JPA para obter o model mutável real (`getDelegateForUpdate()`).

Padrão idêntico ao do `DefaultCachedStoreFactoryProvider` / Infinispan (`org.keycloak.models.cache.infinispan.authorization.*`).

## 5. Serialização (Cached* + Jackson)

### 5.1 Value objects

```
balbucio.keycloak.cache.redis.authz.model/
  CachedResource.java
  CachedResourceServer.java
  CachedScope.java
  CachedPolicy.java
  CachedPermissionTicket.java
  CachedEntityEnvelope.java    // wrapper: { "_gen": <long>, "_payload": <Cached*> }
```

- `CachedEntityEnvelope` envolve o payload real com a geração, para checagem de stale no `get`.
- Campos sensíveis (ex.: regras completas de policy com configuração customizada) são serializados como `Map<String,String>` ou sub-objetos.
- Coleções (scopes de um resource, policies associadas) são **serializadas por valor** (snapshot no momento do cache) — não como referências lazily-carregadas (evita `LazyInitializationException` típica do Hibernate ao desserializar).

### 5.2 ObjectMapper

- Reusar a infraestrutura Jackson do Keycloak (já `provided` no `pom.xml`).
- Um `ObjectMapper` dedicado e cacheado por factory (não por request).
- Mixins podem ser necessários para interfaces de authz (`Policy`, `Resource`) se serializadas diretamente — **preferência**: serializar os `Cached*` POJOs (concretos), evitando mixins.

## 6. Stores — detalhamento

### 6.1 ResourceStore

| Método | Cacheada? | Chave |
|---|---|---|
| `findById(resourceServer, id)` | ✅ | `resource:<id>` |
| `findByName(resourceServer, name)` | ✅ | `resource:<rsId>:name:<name>` |
| `findByNameUri(resourceServer, name, uri)` | ✅ | `resource:<rsId>:name:<name>` (filtro uri client-side) |
| `findByResourceServer(filters, pagination)` | ❌ | delegate JPA |
| `findByOwner / findByScope / ...` | ❌ | delegate JPA (queries dinâmicas) |
| `create(resource)` | write-through | invalidate(rsId) antes + depois do commit |
| `delete(id)` / `delete(resource)` | write-through | idem |

### 6.2 ResourceServerStore

| Método | Cacheada? | Chave |
|---|---|---|
| `findById(id)` | ✅ | `resource-server:<id>` |
| `findByClient(client)` | ✅ | `resource-server:client:<clientId>` |
| `create / save / delete` | write-through | invalidate(rsId) |

### 6.3 ScopeStore

| Método | Cacheada? | Chave |
|---|---|---|
| `findById(resourceServer, id)` | ✅ | `scope:<id>` |
| `findByName(resourceServer, name)` | ✅ | `scope:<rsId>:name:<name>` |
| `findByResourceServer` | ❌ | delegate JPA |
| `create / delete` | write-through | invalidate(rsId) |

### 6.4 PolicyStore

| Método | Cacheada? | Chave |
|---|---|---|
| `findById(resourceServer, id)` | ✅ | `policy:<id>` |
| `findByResource(resourceServer, resource)` | ✅ | `policy:<rsId>:type:*:resource:<resId>` (composição de políticas dependentes) |
| `findByResourceServer / findByType / findByScopes / findByDependentPolicies` | ❌ | delegate JPA |
| `create / delete` | write-through | invalidate(rsId) |

> `findByResource` é a única query cached além de by-id/by-name, porque é chamada a cada avaliação de policy de recurso (hot path do enforcement). O resultado é um Set de policy ids; o adapter resolve cada policy via `findById` (que também é cached).

### 6.5 PermissionTicketStore

| Método | Cacheada? | Chave |
|---|---|---|
| `findById(id)` | ✅ | `permission-ticket:<id>` |
| `findByResourceServer / findByOwner / findByRequester / findByResource` | ❌ | delegate JPA (dinâmicas, alta taxa de mudança em fluxos UMA) |
| `create / delete` | write-through | invalidate(rsId) |

> Permission tickets têm churn alto (criados/consumidos em fluxos UMA). Cache by-id apenas; TTL curto (ex.: 5 min) recomendado.

## 7. Invalidation strategy

### 7.1 Fluxo de escrita (write-through com invalidação)

```
Adapter.create()/delete()/update()
  │
  ├─ 1. cache.invalidate(rsId)          → INCR kc:authz:rs-gen:<rsId>   (pré-escrita)
  ├─ 2. delegate.create()/delete()      → JPA write (dentro da tx do KeycloakSession)
  └─ 3. enlistAfterCompletion:          → on commit: cache.invalidate(rsId) novamente
                                                              (INCR pós-commit, cobre repovoamento concorrente)
```

O **duplo INCR** (antes + depois do commit) cobre a race:
- Leitor concorrente A lê miss entre os passos 1 e 2 → carrega do JPA o valor **antigo** → `put` com gen=N.
- Após commit (passo 3), gen vira N+1 → a entry de A (gen=N) é stale no próximo `get` → recarrega.

### 7.2 Por que cache compartilhado dispensa PUBSUB no core

Como o cache vive no Redis (único para todos os nós Keycloak da região), um `INCR` (ou `DEL`) é instantaneamente visível a todos os nós. **Não há cache local a invalidar** — logo o `ClusterProvider` PUBSUB não participa da camada core.

PUBSUB só volta ao cenário na **Fase A4** (LRU local opcional).

### 7.3 TTL como safety net

- TTL default: `authz.cache.ttl-seconds = 1800` (30 min).
- Cobre edge cases de race não detectados pela geração (ex.: crash entre `put` e `INCR`).
- Permission tickets: TTL menor (`authz.cache.permission-ticket-ttl-seconds = 300`).

### 7.4 Cold start e warm-up

- Cache começa frio; aquece sob demanda (lazy load).
- **Sem** warm-up ativo no boot (seria custo desnecessário; authz config é estática, o cache estabiliza rápido).

## 8. Componentes e classes

```
balbucio.keycloak.cache.redis.authz/
  RedisCachedStoreProviderFactory.java       @AutoService(CachedStoreProviderFactory.class), id="default", IsSupported
  RedisCachedStoreFactoryProvider.java       implements CachedStoreFactoryProvider
    ├─ get*Store() → wrappers sobre delegate = session.getProvider(StoreFactory.class)
    ├─ setReadOnly/isReadOnly → delegate
    └─ close() → delegate.close()

  cache/
    RedisAuthorizationCache.java             → GET/SET/DEL/INCR via RedisConnectionProvider
    AuthorizationCacheKey.java               → util p/ montar chaves (resource/scope/policy/rs/ticket)
    AuthorizationCacheMetrics.java           → RedisMetrics.record(Cache.AUTHZ, Op.*)

  model/
    CachedEntityEnvelope.java                → { _gen, _payload }
    CachedResource.java
    CachedResourceServer.java
    CachedScope.java
    CachedPolicy.java
    CachedPermissionTicket.java

  resource/
    RedisCachedResourceStore.java            → wraps JPAResourceStore
    ResourceAdapter.java                     → wraps CachedResource (read) + delegate (write)
  resourceServer/
    RedisCachedResourceServerStore.java
    ResourceServerAdapter.java
  scope/
    RedisCachedScopeStore.java
    ScopeAdapter.java
  policy/
    RedisCachedPolicyStore.java
    PolicyAdapter.java
  permissionTicket/
    RedisCachedPermissionTicketStore.java
    PermissionTicketAdapter.java
```

### 8.1 Remoção do `NullCachedStoreProviderFactory`

`RedisCachedStoreProviderFactory` **substitui** `NullCachedStoreProviderFactory` (mesmo id `default`, mesma ordem). O arquivo antigo é removido. A sub-flag `kc.cache.redis.authz.enabled`:

- `true` (default quando extensão ativa): usa `RedisCachedStoreProviderFactory` (cache Redis).
- `false`: comportamento equivalente ao `NullCachedStoreProviderFactory` atual (cache desativado, JPA direto) — útil para debug/isolamento.

### 8.2 Métricas

Adicionar a `RedisMetrics.Cache`:

```
public static final String AUTHZ = "authz";
public static final String AUTHZ_GEN = "authzGen";
```

Contadores: `GET` (hit/miss distinguidos por tag adicional `result=hit|miss`), `SET`, `INCR` (invalidação), `DEL`. Reusar `vendor.lettuce.cache` com tag `cache=authz`.

## 9. Configuração

### 9.1 SPI / properties

| Propriedade | Env | Default | Descrição |
|---|---|---|---|
| `kc.cache.redis.authz.enabled` | `KC_CACHE_REDIS_AUTHZ_ENABLED` | `true` | Liga a camada de cache de authz (sub-flag) |
| `kc.cache.redis.authz.ttl-seconds` | `KC_CACHE_REDIS_AUTHZ_TTL_SECONDS` | `1800` | TTL default das entries |
| `kc.cache.redis.authz.permission-ticket-ttl-seconds` | `KC_CACHE_REDIS_AUTHZ_PERMISSION_TICKET_TTL_SECONDS` | `300` | TTL de permission tickets |
| `kc.cache.redis.authz.gen-ttl-seconds` | `KC_CACHE_REDIS_AUTHZ_GEN_TTL_SECONDS` | `604800` | TTL da chave de geração (7 dias) |
| `kc.cache.redis.authz.local-lru.enabled` | `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_ENABLED` | `false` | Liga o LRU local por nó (A4) |
| `kc.cache.redis.authz.local-lru.max-size` | `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_MAX_SIZE` | `1000` | Tamanho máximo do LRU local |
| `kc.cache.redis.authz.local-lru.ttl-seconds` | `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_TTL_SECONDS` | `30` | TTL local por entry (segundos) |

Lidas por env/property no `init()` da factory (`AuthorizationCacheConfig.load()`).

### 9.2 Compatibilidade

- Funciona em `standalone`, `sentinel` e `cluster`.
- Em **cluster**: `GET`/`SET`/`DEL`/`INCR` são single-key — não há `MULTI/EXEC` cross-slot. A geração (`rs-gen:<rsId>`) e as entries (`resource:<id>`) podem cair em slots diferentes, mas isso é aceitável: a checagem de geração é best-effort (stale → recarrega), não transacional.

## 10. Entrega em fases

### Fase A0 — Esqueleto + infra de cache
- `RedisAuthorizationCache` (GET/SET/DEL/INCR + envelope + check de geração).
- `CachedEntityEnvelope` + `ObjectMapper` configurado.
- `RedisCachedStoreProviderFactory` + `RedisCachedStoreFactoryProvider` (delegando 1:1 ao JPA, ainda sem cache — só esqueleto).
- Remoção de `NullCachedStoreProviderFactory`.
- Sub-flag `authz.enabled`.
- **Validação:** smoke — authz funcional (login UMA, policy enforcement) sem regressão vs. `NullCachedStore`.

### Fase A1 — ResourceStore + ResourceServerStore
- `CachedResource`, `CachedResourceServer`.
- `RedisCachedResourceStore` (findById/findByName/findByNameUri cached).
- `RedisCachedResourceServerStore` (findById/findByClient cached).
- Invalidation by generation (INCR duplo).
- **Validação:** teste de modelo (create → findById cached → update → cache invalidated → re-read fresh); smoke multi-node (node A invalida, node B vê na próxima leitura do Redis compartilhado).

### Fase A2 — ScopeStore + PolicyStore
- `CachedScope`, `CachedPolicy`.
- `RedisCachedScopeStore`, `RedisCachedPolicyStore` (incl. `findByResource`).
- **Validação:** avaliação de policy end-to-end (token com permissions); assert de hit ratio de cache nas métricas.

### Fase A3 — PermissionTicketStore
- `CachedPermissionTicket`.
- `RedisCachedPermissionTicketStore` (findById only, TTL curto).
- **Validação:** fluxo UMA completo (request ticket → grant → access).

### Fase A4 — LRU local opcional + PUBSUB invalidation (performance)
- LRU in-memory por nó (mapa bounded com política LRU) sobre o `RedisAuthorizationCache`.
- Invalidation do LRU local via PUBSUB em canal dedicado `kc:authz:invalidation`.
- Ativação via `kc.cache.redis.authz.local-lru.enabled` (default `false`), `kc.cache.redis.authz.local-lru.max-size` e `kc.cache.redis.authz.local-lru.ttl-seconds`.
- **Validação:** benchmark de latência de decisão de policy com/sem LRU local; smoke de invalidação cross-node do LRU.

### Fase A5 — Robustez e produção
- Testes de modelo completos (todas as 5 stores, com/sem cache).
- Benchmark de carga (k6/JMeter) comparando `NullCachedStore` vs. cache Redis vs. cache Redis + LRU.
- Failover: matar Redis → fallback transparente ao delegate JPA (cache miss tratado como erro não-fatal).
- Documentação operacional (README + seção de configuração).

Cada fase só inicia após a anterior validada (smoke/integração).

## 11. Testes

### 11.1 Unit
- `RedisAuthorizationCache` (GET/SET/DEL/INCR + envelope/generation) contra Redis real via Testcontainers.
- `Cached*` (de)serialização Jackson — round-trip de entidades representativas.
- `AuthorizationCacheKey` — construção e colisão de chaves.

### 11.2 Modelo / integração
- Portar o padrão do testsuite existente (`KeycloakModelTest`, `keycloak-quarkus-server` test scope, H2, `testcontainers-redis`):
  - **Resource:** create → findById (cache hit), update → findById (cache invalidated, fresh), delete.
  - **ResourceServer:** findByClient cached; invalidate on update.
  - **Scope / Policy:** idem; `PolicyStore.findByResource` cached.
  - **PermissionTicket:** create → findById → consume → findById miss.
  - **Geração:** write em resource inválida todas as entries daquele resource server (mesmo de tipos diferentes).
  - **Race (best-effort):** leitura concorrente durante escrita → entry stale detectada via geração.

### 11.3 Smoke (Docker)
- `docker-compose.multinode.yml`: habilitar authz em um realm/client, criar resource + policy, validar:
  - Login com policy enforcement ativo (sem erros).
  - Métricas `/metrics` mostram `vendor.lettuce.cache{cache=authz}` com hits.
  - Update de policy via Admin Console → próxima decisão reflete a mudança (invalidação).
  - Persistência após `docker compose restart keycloak` (cache reaquece do JPA).

## 12. Trade-offs e riscos

| Item | Avaliação |
|---|---|
| **Stale reads** | Mitigados por geração (INCR duplo) + TTL safety net. Janela máxima de staleness ≈ TTL (aceitável para authz config, que muda raramente). |
| **Latência extra no miss** | Miss adiciona 1 `GET` (miss) + 1 `SET` (repopulação) sobre o path JPA. Para hot keys (resource/policy by-id), hit ratio esperado > 95% → latência líquida **menor** (evita query JPA). |
| **Serialização de grafos complexos** | Policies com config de aggregator/provider podem ter sub-objetos. Serialização como `Cached*` (snapshot plano) evita `LazyInitializationException`. Risco: campo não coberto → NPE na desserialização. Mitigação: testes de round-trip por tipo de policy (role, js, time, regex, aggregated, scope, resource, permission). |
| **Cluster mode** | Geração e entries em slots distintos → checagem best-effort. Aceitável (authz é eventual-consistent por natureza no cache-aside). |
| **Memory no Redis** | Entries de authz são pequenas (< 1 KB típico) e poucas (escala com nº de resources+policies, não com usuários). TTL controla crescimento. |
| **Fallback em falha de Redis** | `RedisAuthorizationCache` trata exceções de conexão como **miss** (não-fatal) → cai no delegate JPA. Authz permanece funcional mesmo com Redis indisponível. |

## 13. Decisões registradas

### 13.1 Cache-aside (não source-of-truth)
- Diferente das 4 regiões de sessão (Redis = fonte da verdade), authz permanece em **JPA** como store persistente. Redis é apenas cache.
- Razão: authz config é gerenciada via Admin Console/API (transacional, com integridade referencial e queries complexas); migrar para source-of-truth traria custo desproporcional ao benefício.

### 13.2 String/JSON sobre hash/`MapEntity`
- Authz é read-mostly; updates são invalidações (DEL/INCR), não merges field-level. `SET EX` de String JSON é mais simples e eficiente que `HSET`+CAS.

### 13.3 Invalidation by generation (não por tracking de chaves)
- `INCR rs-gen:<rsId>` invalida todas as entries daquele resource server sem manter um Set de chaves (overhead de `SADD`/`SMEMBERS` em cada `put`).
- Trade-off: entries stale ocupam espaço até o TTL expirar (aceitável — são pequenas e poucas).

### 13.4 Sem PUBSUB no core (cache compartilhado)
- O cache vive no Redis; `DEL`/`INCR` são globalmente visíveis. PUBSUB só se justifica na camada LRU local opcional (Fase A4).

### 13.5 Queries não cacheadas
- `findByResourceServer`, `findByOwner`, `findByScopes`, etc. são paginadas/dinâmicas e usadas quase exclusivamente no Admin Console (baixa frequência). Caching de result-sets adicionaria complexidade de invalidação sem benefício mensurável. Mesma escolha da implementação Infinispan nativa.

## 14. Fora de escopo

- **Multi-region active-active para authz** — cache de authz segue a topologia single-region da camada core. Invalidation cross-region depende de `SPEC-MULTI-REGION` (bridge de eventos). Se A4 (LRU local + PUBSUB) for implementado, a invalidação cross-region reusará a mesma bridge.
- **Cache de queries/resultado de avaliação de policy** (`AuthorizationProvider.evaluate`) — esta spec cached entidades, não decisões. Cache de decisão é camada superior (PolicyEnforcer side) e fora do escopo da extensão.
- **Source-of-truth em Redis** — JPA permanece o store persistente.
- **Import/warm-up bulk no boot** — cache aquece sob demanda.
