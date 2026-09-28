# Clustering (multi-nó)

A extensão coordena vários nós Keycloak que compartilham o **mesmo Redis**. Sticky session não é necessária: sessões e objetos efêmeros já estão no Redis.

Isso cobre HA / escala horizontal na mesma instalação. Não há suporte a multi-region active-active.

## ClusterProvider via PUBSUB

Factory: `RedisPubsubClusterProviderFactory` (id `infinispan`, order `3`)

| Recurso | Chave / canal (relativo) |
|---------|--------------------------|
| Eventos de invalidação | canal PUBSUB `cluster:events` |
| Conclusão de task async | canal PUBSUB `cluster:task-finished` |
| Distributed locks | `cluster:lock:<task>` |
| Cluster start time | `cluster:startTime` |

### Eventos

Serializa eventos de invalidação do Keycloak (realm, user, client, role, group, client scope, federation links, consents, etc.) com Jackson mixins e publica no canal PUBSUB. Os outros nós aplicam a invalidação nos caches locais.

Single-site assumido: `DCNotify` é transportado no envelope mas sem filtro multi-DC. Se o core
pedir `LOCAL_DC_ONLY`/`ALL_BUT_LOCAL_DC`, o provider loga `warn` e entrega mesmo assim
(Fase 1.3). Não há suporte a multi-region active-active.

### Distributed locks

`executeIfNotExecuted` usa `SET NX EX` + unlock Lua tokenizado. `executeIfNotExecutedAsync` registra um `TaskCallback` e, ao liberar o lock, publica no canal `cluster:task-finished` (payload `task::<taskKey>`) para completar waiters neste nó e nos demais.

Espera fatiada (Fase 1.4): o waiter checa o lock a cada ~2s sem passar do teto
`taskTimeoutInSeconds`. Se o lock sumir em dois intervalos seguidos sem o `task-finished`
chegar (holder morreu sem unlock/publish), o waiter retorna `false` antecipadamente em vez
de esperar o timeout cheio. Falha de leitura do lock é fail-open (segue esperando).

### Reconnect PUBSUB (Fase 1.2)

PUBSUB não tem replay: invalidações publicadas durante um outage são perdidas para o nó
surdo. No reconnect (detectado via `RedisConnectionStateListener`), cada canal reconcilia
seu L1 **próprio**:

| Canal | Ação no reconnect |
|-------|-------------------|
| `authz:invalidation` | limpa o LRU local compartilhado (próxima leitura recarrega do L2) |
| `public-keys:invalidation` | limpa o L1 local (próxima leitura recarrega do L2) |
| `cluster:events` | só métrica + log — sem L1 próprio; `realms`/`users` stock convergem sob demanda no próximo evento |

Implementação: `connection/PubSubReconnect` (fail-open; primeira conexão ignorada, pois o L1
nasce vazio). Ver `vendor.lettuce.cluster.events{outcome="reconnected"/"resync_cleared"}`.

### Sticky session

`DisabledStickySessionEncoderProvider` remove o anexo de route sticky. Com sessão no Redis, qualquer nó pode atender o request.

### Public keys

`RedisPublicKeyStorageProvider` usa Redis L2 + L1 local. Invalidação cross-node no canal `public-keys:invalidation`. Fail-open para o `PublicKeyLoader` se Redis falhar.

## Topologias Redis × clustering Keycloak

| Cenário | Suportado |
|---------|-----------|
| Vários Keycloak + um Redis/Valkey standalone | Sim |
| Vários Keycloak + Redis Sentinel | Sim |
| Vários Keycloak + Redis Cluster | Sim (índices com consistência eventual) |
| Multi-region active-active | Não |

## Smoke multi-nó

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.multinode.yml up
```

- Nó 1: http://localhost:8080
- Nó 2: http://localhost:8081

Validar login em um nó e continuidade da sessão no outro, além de mudanças de realm/user/client refletidas entre nós via `cluster:events`.

## Classes principais

| Classe | Papel |
|--------|-------|
| `RedisPubsubClusterProvider` | PUBSUB + locks |
| `RedisPubsubClusterProviderFactory` | Lifecycle do subscriber |
| `ClusterEventSerializer` | Serialização Jackson dos eventos |
| `events/*Mixin` | Mixins por tipo de evento |
| `DisabledStickySessionEncoderProvider` | Desliga sticky |
| `RedisPublicKeyStorageProvider` | Public keys Redis L2 + L1 |
