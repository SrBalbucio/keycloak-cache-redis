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

### Distributed locks

`executeIfNotExecuted` usa `SET NX EX` + unlock Lua tokenizado. `executeIfNotExecutedAsync` registra um `TaskCallback` e, ao liberar o lock, publica no canal `cluster:task-finished` (payload `task::<taskKey>`) para completar waiters neste nó e nos demais.

Residual: se o holder morrer sem unlock e o TTL do lock expirar sem publish, waiters podem aguardar até o timeout da task.

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
