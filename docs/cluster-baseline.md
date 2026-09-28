# Baseline multinó do cluster bus (Fase 0.3) + SLO proposto

Roteiro manual para medir o stale real de `realms`/`users` locais entre nós antes de qualquer
mudança de semântica. Pré-requisito: métricas da Fase 0 ativas
(`vendor.lettuce.cluster.events`, `vendor.lettuce.cluster.lag`, `vendor.lettuce.cluster.task`).

## Topologia

`docker-compose.multinode.yml`: 2× Keycloak 26.7.1 (`KC_CACHE=local` +
`KC_COMMUNITY_REDIS_CACHE_ENABLED=true`) + Valkey compartilhado. Sticky session
desnecessária (`DisabledStickySessionEncoderProvider`).

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.multinode.yml up
```

- Nó 1: http://localhost:8080 (admin/admin) · métricas http://localhost:9000/metrics
- Nó 2: http://localhost:8081 · métricas http://localhost:9001/metrics
- Os dois nós compartilham **o mesmo Postgres e o mesmo Valkey**: sessões, entidades e
  chaves de realm são comuns — continuidade de sessão e convergência de entidades são
  observáveis de verdade. (Antes o compose usava um H2 por nó, o que impedia qualquer
  SSO cross-node: UUIDs de usuário e chaves de realm diferentes por banco.)
- Primeiro boot roda a migração do banco (~1 min); aguarde os dois "started".

## Cenários

### A. Sanidade de sessão (prova que Redis é fonte da verdade)

1. Login no nó 1 (conta `admin` ou usuário de teste).
2. Requisitar recurso/sessão no nó 2 sem novo login.
3. Esperado: sessão válida nos dois nós, sem sticky.

### B. Propagação realm/client/role/group/user (o que a Fase 0 mede)

### B. Propagação realm/client/role/group/user (o que a Fase 0 mede)

Funcional + transporte (DB compartilhado — a convergência é observável de verdade).

1. Snapshot antes: `curl -s localhost:9000/metrics | grep vendor_lettuce_cluster_events`
   (nó 1) e o mesmo no `:9001` (nó 2). Anote os valores por `eventKey`/`outcome`.
2. No nó 1 (Admin Console): alterar displayName do realm + Save; criar um client; criar uma
   role; atualizar um atributo de usuário. Cada escrita emite invalidações
   (`REALM_INVALIDATION_EVENTS`, `USER_INVALIDATION_EVENTS`, ...).
3. No nó 2: reler cada objeto (realm, client, role, usuário) e anotar se/quando apareceu
   (cronômetro de celular vale).
4. Recapturar as métricas nos dois nós e confrontar por `eventKey`:
   - `outcome="sent"` no nó 1 vs `outcome="delivered"` no nó 2 — devem bater 1:1 por escrita;
     `dropped_no_listener` só é esperado para eventKeys sem emissor stock ativo, ver
     [cluster-event-matrix.md](cluster-event-matrix.md).
   - `vendor.lettuce.cluster.lag` (`vendor_lettuce_cluster_lag_seconds_count/sum/max`):
     média = `sum/count`; `max` = pico. (p99 exigiria Prometheus; média+max bastam aqui.)
   - Se algum publish em regime disser `published to 0 subscribers` no log com os dois nós
     saudáveis, reporte — em regime, o outro nó deve estar subscrito.
5. Esperado: visível no outro nó em ~segundos (PUBSUB), sem restart.
6. Repita 2–3x para noção de variância.

### C. Outage do Redis 30s (janela de stale)

1. `docker compose -f docker-compose.multinode.yml stop valkey` (30s). Durante o outage, o
   Admin Console deve apresentar erros/timeouts (sessões vivem no Redis — comportamento
   esperado, anote o que observar).
2. `docker compose -f docker-compose.multinode.yml start valkey`. Nos logs, procure
   `PUBSUB reconnected` (Fase 1.2). Nas métricas: `outcome="reconnected"` ≥ 1 nos canais
   ativos (`cluster`, `public-keys`; `authz-lru` só se o LRU local estiver ligado —
   desligado por padrão).
3. Pós-volta: faça uma alteração de realm no nó 1 e confira `sent`→`delivered` + lag normal
   — a prova de recuperação é **convergir de novo sem restartar o Keycloak**.
4. Ressalva by-design (sem replay no PUBSUB): uma invalidação publicada *durante* o outage
   nunca chega — o bound de stale nesses casos é a *próxima* invalidação. Se durante o
   outage alguma escrita no nó 1 tiver retornado sucesso, confira se o efeito correspondente
   ficou pendente até a próxima escrita.
5. Anote: `publish_error`/`deser_error` (idealmente 0 fora do outage) e o tempo até o
   `reconnected` aparecer.

### D. Locks async (residual conhecido)

Sem provocação manual: consulte
`vendor_lettuce_cluster_task_seconds_count{outcome="timeout"}` nos dois nós
(`:9000` e `:9001`) — esperado `0`
(ou métrica ausente) em operação normal. Se aparecer `timeout`, reporte (indica holder
morto sem `publish task-finished`; a Fase 1.4 já encurta esses casos).

## SLO proposto (a confirmar com os números de B/C)

| Sinal | SLO proposto |
|-------|--------------|
| Propagação `REALM_*` / `USER_*` entre nós (sem outage) | p99 < 2s (`cluster.lag`), `delivered/sent` ≈ 1 por `eventKey` ativo |
| Recuperação após outage Redis ≤ 60s | sem restart do Keycloak; stale bound documentado (Fase 1.2 define o bound automático) |
| `deser_error` | 0 em versão homogênea; >0 só em rolling upgrade multi-versão (gap 0.4.5) |
| `task{outcome="timeout"}` | 0 em operação normal; >0 indica holder crashado (residual Fase 1.4) |

## Saída da Fase 0.3

- [ ] A: sessão criada no nó 1 válida no nó 2 sem novo login.
- [ ] B: tabela `sent` (nó 1) vs `delivered` (nó 2) por `eventKey` + lag médio/max por `eventKey` (3 rodadas).
- [ ] C: comportamento observado no outage; `reconnected` por canal; convergência pós-volta sem restart (sim/não + tempo).
- [ ] D: `timeout` em `cluster.task` (esperado 0).
- [ ] Qualquer `Failed to publish` / `Failed to handle` nos logs.
- [ ] Se B/C dentro do SLO, Fase 1 vira hardening pequeno; se há perda sistemática, priorizar 1.2.

## Gaps catalogados na Fase 0 (entrada da Fase 1)

1. ~~`FAILS_ON_UNKNOWN_PROPERTIES` ligado~~ ✅ **Resolvido na Fase 1.1**: mapper tolerante +
   mixin ausente de `UserVerifiableCredentialsUpdatedEvent` adicionado
   (`ClusterEventSerializerTest#unknownExtraFieldsOn*AreIgnored`, round-trip do evento VC).
2. ~~Sem replay após reconnect (prova em `RedisLostInvalidationIntegrationTest`). Melhoria:
   reconciliação/clear conservador no reconnect (Fase 1.2).~~ ✅ **Resolvido na Fase 1.2**
   para L1 próprios: `connection/PubSubReconnect` limpa authz-LRU e public-keys-L1 no
   reconnect (`reconnectListenerReconciles*`, métricas `reconnected`/`resync_cleared`).
   Residual documentado: `realms`/`users` stock sem replay convergem sob demanda
   (`docs/limitations.md`).
3. `DCNotify` transportado, sem filtro (single-site assumido). Melhoria: honrar ou
   documentar + `warn` se ≠ `ALL_DCS` (Fase 1.3).
4. ~~Waiter async preso até timeout se holder morre sem `publish task-finished`
   (`RedisPubsubClusterProvider`, `docs/limitations.md`). Melhoria: completar como
   `timeout` + métrica (parcialmente instrumentado na Fase 0; semântica na Fase 1.4).~~ ✅
   **Resolvido na Fase 1.4**: espera fatiada (~2s) com saída antecipada quando o lock some
   em dois intervalos seguidos; teto total inalterado
   (`waiterReturnsEarlyWhenLockVanishesWithoutCompletion` unit + integração).
5. ~~Cobertura de teste só `ClientAddedEvent` no publish→deliver inter-nó~~ ✅ **Resolvido
   na Fase 1.3**: `RedisPubsubClusterProviderIntegrationTest#
   allSupportedEventTypesAreDeliveredInterNode` publica os 23 tipos suportados do nó A e
   confere entrega ordenada no nó B. `DCNotify` ≠ `ALL_DCS` agora gera `warn` explícito
   (single-site assumido) em vez de descarte silencioso.
