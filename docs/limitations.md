# Limitações

Comportamentos conhecidos e restrições da implementação atual.

## Sessões

- **Sem migração Infinispan → Redis.** `importUserSessions` / `loadPersistentSessions` são no-ops. Após ativar a extensão, usuários precisam reautenticar.
- **Sessões offline** vivem no Redis; não há preload a partir do banco como no caminho Infinispan clássico.
- Em modo Redis **cluster**, índices secundários não usam `MULTI/EXEC` — consistência eventual dos SETs.

## Authorization

- Cache-aside: o JPA permanece a fonte da verdade; Redis pode estar temporairemente dessincronizado até TTL/invalidação.
- Falhas de Redis no caminho de cache são **não-fatais** (tratadas como miss).
- LRU local (quando ligado) introduz uma janela curta de stale por nó, mitigada por TTL e PUBSUB.

## Clustering

- Coordenação multi-nó exige o **mesmo Redis lógico** (standalone, sentinel ou cluster Redis).
- **Public key storage** é local ao processo, não compartilhado no Redis.
- Sticky session é desnecessária e está desabilitada pelo shim da extensão.

## Fora do escopo atual

- Multi-region active-active (replicação cross-DC, resolução de conflitos, failover geográfico).
- Redis separado para authz vs sessões (ambos usam o mesmo `RedisConnectionProvider`).
- Migração assistida de sessões existentes.

## Requisitos operacionais

- `KC_CACHE=local` é obrigatório.
- `KC_COMMUNITY_REDIS_CACHE_ENABLED=true` (ou a system property equivalente) é obrigatório para ativar os providers.
- Keycloak alvo: **26.7.1**.
