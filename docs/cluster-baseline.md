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

- Nó 1: http://localhost:8080 (admin/admin)
- Nó 2: http://localhost:8081
- Métricas (se habilitadas no Keycloak): `/metrics` em cada nó.

## Cenários

### A. Sanidade de sessão (prova que Redis é fonte da verdade)

1. Login no nó 1 (conta `admin` ou usuário de teste).
2. Requisitar recurso/sessão no nó 2 sem novo login.
3. Esperado: sessão válida nos dois nós, sem sticky.

### B. Propagação realm/client/role/group/user (o que a Fase 0 mede)

1. No nó 1 (Admin Console): alterar displayName do realm / criar client / criar role /
   atualizar grupo / atualizar atributo de usuário.
2. No nó 2: reler o objeto (realm JSON, client list, role, members, user).
3. Coletar por `eventKey` (`REALM_INVALIDATION_EVENTS`, `USER_INVALIDATION_EVENTS`, ...):
   - `vendor.lettuce.cluster.events{outcome="sent"}` no nó 1 vs `{outcome="delivered"}` no nó 2
     (devem bater 1:1 por escrita; `dropped_no_listener` só é esperado para eventKeys sem
     emissor stock ativo, ver [cluster-event-matrix.md](cluster-event-matrix.md)).
   - `vendor.lettuce.cluster.lag{eventKey=...}` p50/p99 (publish→deliver).
4. Esperado hoje: visível no outro nó em ~segundos (PUBSUB), sem restart.

### C. Outage do Redis 30s (janela de stale)

1. `docker compose -f docker-compose.multinode.yml stop valkey` (30s), fazer 1 escrita de
   realm no nó 1 durante o outage, voltar Valkey.
2. Esperado **atual** (comportamento caracterizado): a invalidação publicada sem subscriber
   é perdida (sem replay); o nó surdo fica stale até próximo evento/clear/restart —
   mesma classe de hazard provada para L1 em `RedisLostInvalidationIntegrationTest`.
   Anotar duração do stale observado; é o dado que justifica a Fase 1.2 (reconciliação no reconnect).

### D. Locks async (residual conhecido)

1. Disparar task `executeIfNotExecutedAsync` e matar o holder antes do unlock
   (ou simular via teste com TTL curto).
2. Esperado **atual**: waiters acordam só no `taskTimeoutInSeconds`
   (`docs/limitations.md`); nova métrica `vendor.lettuce.cluster.task{outcome="timeout"}`
   deve contar esses casos.

## SLO proposto (a confirmar com os números de B/C)

| Sinal | SLO proposto |
|-------|--------------|
| Propagação `REALM_*` / `USER_*` entre nós (sem outage) | p99 < 2s (`cluster.lag`), `delivered/sent` ≈ 1 por `eventKey` ativo |
| Recuperação após outage Redis ≤ 60s | sem restart do Keycloak; stale bound documentado (Fase 1.2 define o bound automático) |
| `deser_error` | 0 em versão homogênea; >0 só em rolling upgrade multi-versão (gap 0.4.5) |
| `task{outcome="timeout"}` | 0 em operação normal; >0 indica holder crashado (residual Fase 1.4) |

## Saída da Fase 0.3

- [ ] p50/p99 de `cluster.lag` por `eventKey` em B.
- [ ] Razão `delivered/sent` por `eventKey` em B (aponta perda sistemática vs canal saudável).
- [ ] Duração do stale em C (justificativa da Fase 1.2).
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
