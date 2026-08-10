# Limitações

Comportamentos conhecidos e restrições da implementação atual.

## Sessões

- **Sem migração Infinispan → Redis.** `importUserSessions` / `loadPersistentSessions` são no-ops. Após ativar a extensão, usuários precisam reautenticar.
- **Sessões offline** vivem no Redis; não há preload a partir do banco como no caminho Infinispan clássico.
- Em modo Redis **cluster**, se entity e índices caírem em slots diferentes (`CROSSSLOT`), o CAS do hash e os `SADD`/`SREM` dos índices deixam de ser atômicos (fallback best-effort). Login failures usam hash-tag por realm (`{realmId}`) para colocalizar entity + índice.
- Índices SET recebem TTL alinhado à expiração da entidade e são limpos no expire lazy (leitura) e em deletes; updates aplicam delta de membership (SREM dos membros antigos).

## Authorization

- Cache-aside: o JPA permanece a fonte da verdade; Redis pode estar temporairemente dessincronizado até TTL/invalidação.
- Falhas de Redis no caminho de cache são **não-fatais** (tratadas como miss).
- LRU local (quando ligado) introduz uma janela curta de stale por nó, mitigada por TTL e PUBSUB.

## Clustering

- Coordenação multi-nó (sessões / cluster / public-keys) exige o **mesmo Redis lógico** no connection `default`.
- Public keys usam Redis L2 + L1 local com PUBSUB `public-keys:invalidation`.
- Sticky session é desnecessária e está desabilitada pelo shim da extensão.

## Entity cache MVP

- Desligado por padrão (`KC_CACHE_REDIS_ENTITY_ENABLED`). Quando ligado, só indexa lookups quentes (username/email/realm name/clientId); não há parity com o grafo Infinispan de roles/groups.
- Detalhes: [entity-cache.md](entity-cache.md).

## Fora do escopo atual

- Multi-region active-active (replicação cross-DC, resolução de conflitos, failover geográfico).
- Migração assistida de sessões existentes.

## Requisitos operacionais

- `KC_CACHE=local` é obrigatório.
- `KC_COMMUNITY_REDIS_CACHE_ENABLED=true` (ou a system property equivalente) é obrigatório para ativar os providers.
- Keycloak alvo: **26.7.1**.
