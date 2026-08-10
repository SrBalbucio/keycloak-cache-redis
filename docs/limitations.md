# Limitações

Comportamentos conhecidos e restrições da implementação atual.

## Sessões

- **Sem migração Infinispan → Redis.** `importUserSessions` / `loadPersistentSessions` são no-ops. Após ativar a extensão, usuários precisam reautenticar.
- **Sessões offline** vivem no Redis por padrão. Com `persistOfflineSessions=true`, há write-through JPA + preload no boot.
- User/auth sessions e login-failure usam hash-tag `{realmId}` para colocalizar entity + índices no Redis Cluster. Em slots divergentes ainda há fallback best-effort (`CROSSSLOT`).
- Índices SET recebem TTL alinhado à expiração da entidade e são limpos no expire lazy (leitura) e em deletes; updates aplicam delta de membership (SREM dos membros antigos).

## Authorization

- Cache-aside: o JPA permanece a fonte da verdade; Redis pode estar temporairemente dessincronizado até TTL/invalidação.
- Falhas de Redis no caminho de cache são **não-fatais** (tratadas como miss).
- LRU local (quando ligado) introduz uma janela curta de stale por nó, mitigada por TTL e PUBSUB.

## Clustering

- Coordenação multi-nó (sessões / cluster / public-keys) exige o **mesmo Redis lógico** no connection `default`.
- Public keys usam Redis L2 + L1 local com PUBSUB `public-keys:invalidation`.
- Locks async (`executeIfNotExecutedAsync`) completam via PUBSUB `cluster:task-finished`. Se o holder morrer sem unlock, waiters podem timeout quando o TTL do lock expira sem publish.
- Sticky session é desnecessária e está desabilitada pelo shim da extensão.

## Entity cache (realm/user)

- **Removido.** O realm/user cache Redis era incompatível com providers core do Keycloak
  (cast hard-coded para `RealmCacheSession`/`UserCacheSession`) e, quando desligado,
  sobrescrevia o slot `default` da stock e deixava `getProvider(CacheRealmProvider.class)`
  nulo. As variáveis `KC_CACHE_REDIS_ENTITY_ENABLED`/`KC_CACHE_REDIS_ENTITY_TTL_SECONDS` não
  têm mais efeito. Realm/user cache voltam ao Infinispan local (stock).
- Detalhes e o mecanismo completo: [entity-cache.md](entity-cache.md) e o Defeito B em
  [spec-authsession-and-realm-cache-fix.md](spec-authsession-and-realm-cache-fix.md).

## Fora do escopo atual

- Multi-region active-active (replicação cross-DC, resolução de conflitos, failover geográfico).
- Migração assistida de sessões existentes.

## Requisitos operacionais

- `KC_CACHE=local` é obrigatório.
- `KC_COMMUNITY_REDIS_CACHE_ENABLED=true` (ou a system property equivalente) é obrigatório para ativar os providers.
- Keycloak alvo: **26.7.1**.
