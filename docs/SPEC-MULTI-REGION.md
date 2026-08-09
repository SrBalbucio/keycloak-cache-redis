# Especificação — Multi-region Active-Active

Versão: 1.1 (draft — premissas validadas com docs oficiais Redis)
Status: Proposta
Baseline: `docs/SPEC.md` (v1.0, single-region) + código atual (4 regiões + `ClusterProvider` PUBSUB + métricas + modos `standalone`/`sentinel`/`cluster`)

## 1. Contexto, objetivos e premissas

O SPEC v1.0 (seção 12) declara multi-region active-active como **fora de escopo**. Esta especificação estende o design para atender 2+ regiões geográficas servindo tráfego simultaneamente.

**Objetivo operacional:** usuários são roteados para a região mais próxima (geo-DNS / latency-based routing); a queda de uma região é transparente para a sessão (failover sem reautenticação); as sessões continuam sendo a fonte da verdade no Redis.

**Premissa central:** com `KC_CACHE=local` + a extensão ativa não existe Infinispan, logo **não há JGroups RELAY2 / multi-site nativo** do Keycloak. A coordenação cross-region (invalidação de caches locais e consistência de dados de sessão) passa a ser responsabilidade desta extensão.

**Requisitos de qualidade (não-funcionais):**

| Métrica | Meta |
|---|---|
| Latência | Operações de sessão atendidas na região local na esmagadora maioria das vezes (afinidade de roteamento) |
| RPO | ≤ 0 para sessões (nenhuma perda visível após failover de região), ou RPO documentado da opção escolhida |
| RTO | ≤ minutos (tempo de roteamento + health check do load balancer) |
| Integridade | Single-use tokens não podem ser consumidos 2× mesmo sob failover; contadores de `loginFailures` não podem perder incrementos; invalidação de realm/client/user propaga entre regiões |

## 2. Topologias possíveis

### Opção A — Store global CRDT (Redis Enterprise Active-Active / CRDB)

Todas as regiões leem/escrevem o **mesmo banco lógico global**. O fornecedor replica entre regiões e resolve conflitos com CRDTs:
- Hash → **LWW por campo** (last-write-wins em cada campo do hash).
- `HINCRBY` → **contador CRDT** (incrementos não se perdem).
- Set → **set CRDT** (união de membros).
- Stream → replicado com resolução por entrada.

- Prós: sessão é única e global; failover transparente; modelo mental simples; chaves já são globais no código atual.
- Contras: lock-in de produto (Redis Enterprise); latência cross-region em acessos remotos (mitigada por roteamento); scripts Lua rodam em **effects-replication** (leitura local-only — decisões read-modify-write veem estado stale de outras regiões); PUBSUB cruza regiões mas é **at-most-once** (perde mensagens em partições/re-sync).

### Opção B — Redis por região + replicação assíncrona (active-passive / replica-of)

Região primária recebe escritas; as demais seguem via replicação. Não é active-active de verdade para escritas concorrentes (conflitos são resolvidos por overwrite).

- Prós: Redis/Valkey padrão, sem lock-in.
- Contras: RPO > 0; **split-brain** (duas regiões aceitando escrita); contadores perdem incrementos; `removeIfPresent` não é atômico cross-region; CAS quebra.

### Opção C — Redis por região + sessões regionais + ponte de eventos

Cada região tem Redis próprio; sessões são **regionais**; o `ClusterProvider` faz forwarding de invalidação via ponte externa. Failover exige migrar/reautenticar a sessão.

- Prós: isolamento total, sem CRDT, sem lock-in.
- Contras: sessões não são globalmente acessíveis; failover não é transparente; mais complexo.

### Comparação

| Critério | A (CRDB) | B (replica-of) | C (regional + bridge) |
|---|---|---|---|
| RPO | ~0 | segundos | ~0 (sessão regional) |
| Failover transparente de sessão | Sim | Sim (dados podem estar stale) | Não (reautentica/migra) |
| Contadores (`loginFailures`) | OK (CRDT) | Perde incrementos | OK (isolado) |
| Single-use atômico cross-region | **Não garantido** (eventual) | Não | Sim (local) |
| Invalidação cross-region | Bridge **recomendada** (PUBSUB AA cruza, mas é lossy) | Bridge **obrigatória** (PUBSUB não replica em replica-of) | Bridge **obrigatória** |
| Lock-in | Redis Enterprise | Nenhum | Nenhum |
| Complexidade operacional | Baixa | Média | Alta |

### Recomendação

**Opção A para dados + ponte de invalidação cross-region (seção 5).** É a analogia mais fiel ao modelo "global data + RELAY" do multi-site do Infinispan, e a que o código atual mais se aproxima (chaves já são globais).

- Fallback A2: **Opção B** quando não houver CRDB (Valkey/OSS) — aceitando RPO e o tradeoff de contadores; mitigar contadores via `HINCRBY` apenas na região primária.
- **Opção C** apenas se isolamento regional for um requisito de segurança/compliance (raramente o caso para sessões).

> ⚠️ **Single-use tokens exigem decisão própria (seção 3.4):** com CRDB, `removeIfPresent` não é globalmente atômico. Recomendamos manter essa região de dados em um **Redis de coerência único** (opção 1 da seção 3.4), independentemente da topologia de sessões.

## 3. Modelo de dados e consistência por região de dados

A resolução de conflitos depende da estrutura física: como os dados ficam em **campos de um hash Redis**, o LWW por campo do CRDB já cobre a maioria dos casos — desde que cada dado independente seja um campo separado (é o caso: `n.*` para notes, `cs.<clientId>.*` para client sessions).

### 3.1 `userSessions` / `clientSessions`

| Dado | Campo(s) | Estratégia |
|---|---|---|
| `started`, `state`, `rememberMe` | campos escalares | LWW por campo |
| `lastSessionRefresh` | campo escalar | LWW com **tolerância a clock skew**; aceitar regressão menor (a sessão tem TTL e o refresh token revalida) |
| `notes` | `n.*` (1 campo por nota) | LWW por campo — updates concorrentes em notas diferentes não colidem |
| `clientSessions` | `cs.<clientId>.<field>` | LWW por campo — login concorrente em clientes diferentes (região A e B) converge |
| expiração | `expiration` | `PEXPIREAT` replicado pelo CRDB |

- `MapEntity` hoje faz tracking dirty por campo → em modo LWW isso se traduz em `HSET`/`HDEL` dos campos pendentes (sem reescrever o hash inteiro).
- Índices secundários (Sets) convergem por união (CRDT) — `SADD`/`SREM` em regiões diferentes são seguros.

> **Nota — tipo do campo (importante):** em Active-Active o tipo de um campo de hash é definido pelo comando que o **inicializa**: `HSET`/`HSETEX` → string (LWW/OR-Set); `HINCRBY` → contador CRDT. **Não misturar comandos por campo** (ex.: nunca escrever um campo de contador com `HSET`). O `MapEntity` já respeita isso (notas via `HSET`, contadores via `pendingIncrements`/`HINCRBY`). `HDEL`/`DEL` são suportados (observed-remove; `DEL` reseta counter).

### 3.2 `authenticationSessions`

Curta duração (default 5 min). LWW por campo suficiente; criticidade baixa. `AuthenticationSessionAuthNoteUpdateEvent` deve propagar via bridge (seção 5).

### 3.3 `loginFailures`

| Dado | Estratégia |
|---|---|
| Contadores (`failCount` e afins) | `HINCRBY` → **counter CRDT** no CRDB (incrementos nunca se perdem) |
| Lockout / expiração | `PEXPIREAT` (LWW) |

- O `RedisHashCas` já suporta `increments` (campo → delta) em um único script (seção 4). Em modo LWW, manter `HINCRBY` e **remover o check de version**.
- **Opção B**: incrementos concorrentes se perdem (overwrite) → mitigar restringindo escrita de contadores à região primária ou aceitando o tradeoff documentado.
- Contadores em Active-Active têm teto de **59 bits** (≈ 2^59) — irrelevante para `failCount`.

### 3.4 `singleUseObjects` — caso crítico

Hoje `RedisSingleUseObjectProvider` usa `REMOVE_SCRIPT` (EXISTS + HGETALL + DEL) e `PUT_IF_ABSENT_SCRIPT` — **atômicos apenas no nó local** (contexto: CVE de single-use corrigida no 26.7.1).

Em Active-Active, scripts Lua rodam em **effects-replication mode**: a leitura (`EXISTS`) é **local-only** — não enxerga writes de outras regiões ainda — e apenas o efeito (`DEL`) é replicado. Dois consumidores concorrentes nas regiões A e B podem ambos observar "não consumido" localmente e ambos executar `DEL` → **dupla utilização**. Isso viola a premissa de integridade (reabre a CVE de single-use).

**Opções:**

1. **Redis de coerência único (recomendado):** `singleUseObjects` sempre lê/escreve em um Redis **único, globalmente coerente** (região primária apontada por DNS/config, ou um cluster dedicado). `removeIfPresent` volta a ser atômico de verdade; apenas esta região de dados paga latência cross-region e vira um ponto único — mitigado por redundância da região primária.
2. **Contador CRDT (`HINCRBY consumed`):** quem ler `1` vence. **Rejeitado** — dois leitores concorrentes podem ambos ler `1` (leitura eventual), reabrindo o risco de dupla utilização.
3. **Mover para JPA no banco central:** correto, porém fora do escopo desta extensão.

> Decisão: **opção 1** como padrão, com a latência/SPOF documentados (seção 9).

## 4. O problema do CAS e o modelo de consistência

### 4.1 Por que o CAS atual não sobrevive a multi-region

`RedisHashCas.hsetex` (script Lua) faz read-modify-write: `HGET version` → compara → `HSET`/`HDEL`/`HINCRBY version` → `PEXPIREAT`. Ele assume **leitura imediata do próprio write** e um `version` único e monotônico — válido apenas em um único store com leitura imediata (single-region).

Em Active-Active, **scripts Lua sempre executam em effects-replication mode**: a leitura é local-only (não enxerga writes de outras regiões — a replicação é assíncrona) e apenas os efeitos (`HSET`/`HDEL`/`HINCRBY`) são replicados. Consequências:
1. O `HGET version` local não vê os incrementos de outras regiões → o check de CAS falha em cascata (e `version`, tratado como counter, **soma** os incrementos locais em vez de ser monotônico no sentido esperado);
2. Qualquer decisão read-modify-write baseia-se em **estado stale** → os efeitos replicados podem sobrescrever escritas concorrentes corretas.

Por isso o modo `GLOBAL_LWW` **remove o version/CAS** e usa LWW por campo + contadores CRDT (resolução pelo fornecedor, não pela aplicação).

### 4.2 Novo `ConsistencyMode`

| Modo | Uso | Semântica |
|---|---|---|
| `LOCAL_CAS` | single-region (atual) | `RedisHashCas` como hoje: `expected` version, retry/rebase |
| `GLOBAL_LWW` | multi-region | sem `version`; creates/updates por campo via `HSET`; deletes por campo via `HDEL`; contadores via `HINCRBY`; expiração via `PEXPIREAT`; sem retry CAS, sem `MULTI/EXEC` |

### 4.3 Mudanças na camada de storage

- `RedisHashCas`: novo script/ramo `LWW` (igual ao atual, sem o check de `version` e sem `HINCRBY version`). `hsetex(...)` recebe o modo ou a camada acima escolhe.
- `RedisChangelogTransaction.commitEntity`: em `GLOBAL_LWW`, `expectedVersion = null` e **sem loop de retry/rebase** (falha só em erro de conexão → log + exceção). Contadores continuam como `pendingIncrements` → `HINCRBY`.
- `MapEntity`: tracking dirty/deleted por campo permanece; o tracking de `version` fica inerte em `GLOBAL_LWW`.
- `runIndexBatch`: em `GLOBAL_LWW` nunca usa `MULTI/EXEC` (já é o comportamento do modo cluster).

## 5. Coordenação e invalidação cross-region

### 5.1 Por que PUBSUB não basta

Em Active-Active, **PUBSUB cruza regiões como *replicated effects* (at-most-once)**: uma publicação na região A **pode** chegar aos nós da região B, mas mensagens são perdidas durante partições/re-sync. Em replicação clássica (Opção B) o PUBSUB é estritamente local. Em nenhum dos casos é um mecanismo **garantido** de invalidação: sem forwarding determinístico, caches locais (realm/user/client) ficam stale após atualizações feitas em outra região.

### 5.2 Arquitetura do bridge

- Cada região mantém o canal local `kc:cluster:events` (comportamento atual — PUBSUB intra-região).
- Um **`CrossRegionBridge`** encaminha eventos entre regiões:
  - Publicação local → além do PUBSUB, o bridge encaminha para as outras regiões.
  - Recebimento de outra região → o bridge **re-injeta no canal local** (os nós da região não mudam o comportamento).

### 5.3 Transportes (SPI `CrossRegionTransport`)

| id | Descrição | Uso |
|---|---|---|
| `none` | sem forwarding | single-region (default) |
| `stream` | **Redis Stream** global como log de eventos; `XADD` (modo de ID `strict`, gerando IDs únicos globais) na publicação; **consumer-group por região** (`XREADGROUP`) para re-injetar | default em multi-region (stream replica entre regiões no CRDB) |
| `kafka` / `pubsub-external` | broker externo (Kafka, NATS, SNS/SQS, GCP Pub/Sub) | quando a infra já existe ou para não depender do Redis para coordenação |

> **Notas do `stream` (validadas com docs oficiais):** streams sincronizam entre regiões no Active-Active. **Usar `XREADGROUP`, não `XREAD`** (XREAD pode pular entradas durante re-sync). O estado do consumer group é **parcialmente replicado** — para entrega global "at-least-once / single consumer", o bridge da região consumidora mantém **dedupe local** (Set com TTL dos últimos IDs processados) porque a mesma entrada pode ser entregue a mais de um grupo após re-sync.

**Fluxo `stream`:**

```
nó A1: notify() ──PUBSUB──> canal A (nós da região A)
   └─ CrossRegionBridge (região A): XADD stream {region:A, senderId, eventKey, events...}
stream (replicado p/ região B)
   └─ CrossRegionBridge (região B): XREADGROUP cg:B → filtra region==B → PUBLISH canal B
        └─ nós da região B aplicam invalidação local
```

- **Dedupe/loop prevention:** a mensagem carrega `region` de origem e `senderId`. O bridge descarta mensagens com `region == própria` e **nunca** re-publica no stream o que veio do stream. Como defesa extra contra redelivery (rebalanceamento de consumer group), guardar últimos IDs processados por região em um Set com TTL.
- **Monitoramento:** lag do consumer group e contagem de mensagens repassadas/deduped/dropped (seção 10).

### 5.4 Semântica do `DCNotify`

`ClusterProvider.notify(...)` já recebe `DCNotify`; hoje o `RedisPubsubClusterProvider` apenas o serializa e **ignora no tratamento**. O bridge deve honrar:
- `IGNORE` → evento não cruza a região;
- senão (`ALL` / `NOT_IGNORE`) → encaminha às demais regiões (espelhando o comportamento de relay do Infinispan).

### 5.5 `ClusterEventSerializer`

Adicionar `region` ao `ClusterMessage` (junto de `senderId`). `ignoreSender` continua comparando `nodeId` (UUID global); `region` habilita o filtro amplo do bridge.

> **Nota:** scripts Lua **não são replicados entre regiões** no Active-Active (cada cluster-região tem seu próprio `SCRIPT LOAD`; não há garantia de SHA igual entre regiões). Scripts compartilhados só existem localmente; o fallback de NOSCRIPT já tratado no `RedisHashCas` (re-`LOAD` + retry) é suficiente, mas o spec **não exige** o mesmo SHA em todas as regiões.

### 5.6 Locks e `cluster:startTime`

- `cluster:lock:*` (idempotência de tasks) e `cluster:startTime` **permanecem globais** — é a semântica desejada (uma task roda uma vez na formação; startTime único valida eventos). `SET NX` + TTL já cobrem locks órfãos.
- Nada muda no `executeIfNotExecuted` atual além de confiar no store global.

## 6. Key space e roteamento

- **Chaves globais idênticas** em todas as regiões (mesmo `KC_REDIS_KEY_PREFIX`): a sessão é a mesma entidade lógica e qualquer nó de qualquer região a atende.
- Região é **tag de métrica/log**, nunca parte da chave (exceto Opção C).
- **Roteamento:** geo-DNS / latency-based (Route 53, GCLB, CloudFront). Sticky session já desativada (shim `DisabledStickySessionEncoderProvider`).
- **Failover de usuário:** região A cai → LB desvia para B; a sessão continua válida no store global; as chaves de assinatura são as mesmas (originadas no JPA/DB e expostas via `MapPublicKeyStorageProvider` recarregado no nó B); códigos single-use já consumidos falham corretamente (via Redis de coerência, seção 3.4). Reautenticação só quando a sessão expirou.

## 7. Mudanças por arquivo

Novo pacote `balbucio.keycloak.cache.redis.multiRegion`:

| Arquivo | Mudança |
|---|---|
| `connection/RedisConnectionProvider` | `region()`, `consistency()`; getter do client de coerência (single-use) |
| `connection/DefaultRedisConnectionProviderFactory` | novas props (seção 8); client para stream/coerência quando configurado |
| `RedisHashCas` | script/ramo LWW (sem version) |
| `RedisChangelogTransaction` | `commitEntity` com ramo `GLOBAL_LWW` (sem CAS/retry); `runIndexBatch` sem `MULTI/EXEC` |
| `singleUseObject/RedisSingleUseObjectProvider` | rotear para o Redis de coerência (seção 3.4) |
| `cluster/RedisPubsubClusterProvider` | honrar `DCNotify`; publicar também no bridge; `ignoreRegion` no handler |
| `cluster/ClusterEventSerializer` | `region` no `ClusterMessage` |
| `cluster/RedisPubsubClusterProviderFactory` | instanciar e fechar o bridge/transport |
| `common/RedisKeySpace` | sem mudança (chaves globais) |
| `RedisMetrics` | tag `region` em todas as métricas |

Novos componentes:

- `multiRegion/ConsistencyMode` — enum.
- `multiRegion/crossRegion/CrossRegionTransport` — SPI: `publish(region, message)` / `consume(region, handler)` / `ack`.
- `multiRegion/crossRegion/StreamCrossRegionTransport` — default.
- `multiRegion/crossRegion/ExternalPubSubCrossRegionTransport` — esqueleto para broker externo.
- `multiRegion/crossRegion/CrossRegionBridge` — orquestra publish local → transport, recebimento → re-injeção no canal local, dedupe/loop prevention.

## 8. Configuração

Novas variáveis (prefixo SPI `KC_SPI_REDIS_CONNECTION_DEFAULT_*`):

| Variável | Descrição | Default |
|---|---|---|
| `KC_SPI_REDIS_CONNECTION_DEFAULT_REGION` | id da região (ex.: `sa-east-1`) | _(vazio = single-region)_ |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_CONSISTENCY` | `cas` \| `lww` | `cas` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_CROSS_REGION_TRANSPORT` | `none` \| `stream` \| `kafka` \| … | `none` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_CROSS_REGION_STREAM` | nome do stream global de eventos | `kc:cluster:events` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_CONSISTENCY_REDIS_URI` | Redis de coerência (single-use) quando ≠ principal | _(usa principal)_ |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_CLOCK_SKEW_TOLERANCE_MS` | tolerância de skew p/ LWW | `1000` |
| `KC_COMMUNITY_REDIS_CACHE_MULTI_REGION` | feature gate (ou derivado de `region` + `consistency`) | `false` |

Regra de validação: `multiRegion=true` exige `region` setado e `consistency=lww`; `transport=none` é permitido (invalidação não propaga — só para evolução incremental/validação).

## 9. Failover, split-brain e DR

| Cenário | Comportamento (Opção A + bridge) |
|---|---|
| Queda de uma região (rede/instâncias) | LB drena a região; sessões permanecem no store global; usuários caem na região vizinha e seguem autenticados |
| Queda do Redis de uma região (CRDB) | demais réplicas assumem; failover transparente ao nó |
| Partição de rede entre regiões | CRDB tolera (ambas aceitam escrita, convergem depois); single-use segue na região primária de coerência (único ponto — redundância local exigida) |
| Queda da região de coerência (single-use) | usuários que dependem de single-use (troca de código por token, consentimento pontual) falham até recuperação; demais fluxos intactos. Documentar e mitigar com redundância ativa-passiva da região primária |
| Duplicação/atraso do bridge | caches locais têm TTL (default lifespan do Keycloak) → staleness auto-corrige; lag monitorado |
| Skew de relógio | LWW degrada para "último relógio"; dentro da tolerância configurada é aceitável (seção 3.1) |

- RPO: ~0 (CRDB) / documentado na Opção B. RTO: minutos (roteamento).
- Backup: RDB/AOF por região + restore; reatrelar replicação após recuperação; `cluster:startTime` renegociado na recriação do Redis global.

## 10. Observabilidade

- Tag `region` em todas as métricas `vendor.redis.cache.*` e de latência Lettuce.
- Métricas do bridge: `vendor.redis.crossRegion.published`, `.consumed`, `.lag` (consumer group), `.duplicates`, `.dropped`.
- Contador de conflitos LWW resolvidos (se o fornecedor expuser) como sinal de divergência.
- Logs com `region` (MDC).

## 11. Validação e testes

- **Unit:** `ConsistencyMode`; `RedisHashCas` LWW; serializer com `region`; loop prevention do bridge com transport fake.
- **Integração (Testcontainers):** 2 instâncias Valkey simulando regiões com `consistency=lww` e transport `stream` no mesmo Redis:
  - escreve `userSession` na "região A", lê na "região B";
  - incremento de `loginFailures` concorrente em A e B converge (soma);
  - update de realm/cliente propaga invalidação entre regiões via bridge;
  - duplicação do stream (redelivery) não re-dispara evento.
- **Single-use:** consumo em A e replay em B falha (via Redis de coerência).
- **Caos (manual/docker-compose de referência):** kill de região, partição, skew de relógio, queda do Redis de coerência.
- Nota: CRDB (Redis Enterprise) exige licença — validar em ambiente de spike (MR-0), não em CI.

## 12. Fases de entrega

1. **MR-0 — Spike/validação de fornecedor (bloqueador):** com Redis Enterprise Active-Active (ou fornecedor escolhido), confirmar comportamento de `HSET`/`HINCRBY`/`PEXPIREAT`/Streams em CRDB e o modo effects-replication de Lua (leitura local-only). Confirmar versão e licença. (Premissas já pré-validadas nos docs oficiais — ver Anexo A.)
2. **MR-1 — Modelo de consistência `GLOBAL_LWW`:** `ConsistencyMode`, `RedisHashCas` LWW, `RedisChangelogTransaction` sem CAS, contadores via `HINCRBY`, single-use no Redis de coerência. Sem bridge (invalidação ainda não propaga — aceitável só em validação).
3. **MR-2 — Bridge de invalidação:** `CrossRegionTransport` SPI + `stream` (default) + `region` no serializer + honrar `DCNotify` + loop prevention.
4. **MR-3 — Roteamento e failover:** docs de geo-DNS/health check, runbooks de failover e de coerência.
5. **MR-4 — Robustez/produção:** testes de caos, métricas do bridge/região, CI multi-região, documentação operacional.

Cada fase só inicia após a anterior validada.

## 13. Decisões registradas (ADR)

### 13.1 Topologia
- **Opção A (CRDB) + bridge** para dados; Opção B como fallback (RPO documentado); Opção C só se isolamento for requisito.
- **Single-use fora do CRDB:** Redis de coerência único (seção 3.4). Rejeitado o padrão de contador CRDT por risco de dupla utilização (histórico de CVE).

### 13.2 Consistência
- Multi-region usa **LWW por campo + counter CRDT**, nunca CAS por `version`. O `version` permanece apenas no modo `cas`.

### 13.3 Coordenação
- PUBSUB permanece intra-região; o **bridge** é a única via cross-region de invalidação.
- `cluster:lock:*` e `cluster:startTime` são globais (semântica desejada).

## 14. Fora de escopo / limitações

- Migração de sessões existentes entre regiões / após switchover (`importUserSessions` no-op).
- Authorization Services (mantém desativado, como no SPEC v1.0).
- Vários CRDBs independentes com sharding de sessão por região (Opção C não implementada por padrão).
- Single-use em JPA no banco central.
- `DCNotify` semântico exato: implementar espelhando o relay do Infinispan; validar casos de uso reais em MR-2.

## 15. Riscos e premissas a validar (MR-0)

Status das premissas-chave após validação com docs oficiais (detalhes e fontes no Anexo A):

1. **Lock-in:** CRDB é Redis Enterprise; Valkey/OSS não tem CRDB → implica Opção B (RPO) ou C. **Confirmado.**
2. **Lua em CRDB:** scripts rodam em **effects-replication** — leitura local-only, sem restrição de não-determinismo; scripts **não são replicados** entre regiões (cada região faz seu `SCRIPT LOAD`). Scripts LWW (sem decisão dependente de leitura) são aceitos. **Confirmado** — o fallback NOSCRIPT já cobre o re-LOAD.
3. **`HINCRBY` como counter CRDT:** incrementos somam entre regiões (nunca se perdem); campo contador não pode ser escrito com `HSET`; teto de 59 bits. **Confirmado.**
4. **Streams em CRDB:** replicam entre regiões; usar `XREADGROUP` (XREAD pode pular); estado de consumer group parcialmente replicado → dedupe local obrigatório; `XADD` com ID `strict` p/ IDs únicos globais. **Confirmado.**
5. **Clock skew:** validar tolerância e impacto em `lastSessionRefresh`. **Pendente — requer teste empírico** (docs não quantificam).
6. **Latência/SPOF do Redis de coerência** (single-use): definir redundância (primário+replica na mesma região primária). **Pendente — decisão operacional.**
7. **PUBSUB cross-region:** no Active-Active é *replicated effect* (at-most-once, lossy em partições); não serve como via determinística de invalidação. **Confirmado — mantém a recomendação do bridge.**

## 16. Anexo A — Premissas validadas e fontes (v1.1)

Validação feita em 2026 contra a documentação oficial do Redis Enterprise Active-Active (CRDB).

| # | Premissa | Resultado | Observação / Fonte |
|---|---|---|---|
| A1 | PUBSUB é local à região no Active-Active | ❌ **Corrigido** | PUBSUB **cruza regiões** como *replicated effects*, mas **at-most-once** — mensagens se perdem em partições/re-sync. Não é entrega garantida. Fonte: `redis.io/docs/.../active-active/develop/app-failover-active-active` |
| A2 | Scripts Lua com leitura-decisiva são restritos/não-determinísticos em CRDB | ⚠️ **Ajustado** | Sempre rodam em **effects-replication mode**: leitura **local-only**, sem restrição de não-determinismo. Decisões baseadas em leitura veem estado stale de outras regiões. Fonte: `redis.io/docs/.../active-active/develop/develop-for-aa` |
| A3 | Scripts Lua replicam entre regiões | ❌ **Corrigido** | **Não** são replicados — cada cluster-região carrega seus scripts (`SCRIPT LOAD` por região). Fallback NOSCRIPT cobre. Fonte: docs oficiais Active-Active |
| A4 | `HINCRBY` = contador que soma entre regiões | ✅ Confirmado | Counter CRDT; incrementos nunca se perdem; **tetos**: contador 59 bits, expiração [0, 2^49]. Tipo do campo definido pelo comando inicializador (HSET=string LWW, HINCRBY=counter). Fonte: `redis.io/docs/.../active-active/develop/data-types/hashes` |
| A5 | `HDEL`/`DEL` em hashes Active-Active | ✅ Confirmado | Suportados (observed-remove); `DEL` reseta counter. Fonte: `redis.io/docs/latest/commands/hdel` |
| A6 | Streams replicam entre regiões | ✅ Confirmado | Streams sincronizam entre regiões; **usar `XREADGROUP`** (XREAD pode pular entradas); estado de consumer group **parcialmente replicado** → dedupe local obrigatório; `XADD` modo `strict` p/ IDs únicos globais. Fonte: `redis.io/docs/.../active-active/develop/data-types/streams` |
| A7 | Single-use cross-region é atômico em CRDB | ❌ Confirmado o problema | Leitura local-only + replicação assíncrona → **dupla utilização possível**. Mantém a recomendação de **Redis de coerência único** (seção 3.4). Fonte: mesma de A2 |
| A8 | `version` (counter) é monotônico global | ❌ Confirmado o problema | Em AA o `version` **soma** incrementos locais; não é monotônico no sentido esperado pelo CAS → CAS inviável em multi-region (seção 4). Fonte: A4 |

- Recomendação de mitigação resultante: **bridge de invalidação determinístico** (seção 5) + **LWW por campo / counter CRDT** (seção 3) + **single-use fora do CRDB** (seção 3.4).
