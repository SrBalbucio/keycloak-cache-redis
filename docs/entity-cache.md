# Entity cache MVP (Redis)

Cache-aside de **índices de lookup** para user/realm/client, opcional e desligado por padrão.

## Ativação

```bash
KC_CACHE_REDIS_ENTITY_ENABLED=true
KC_CACHE_REDIS_ENTITY_TTL_SECONDS=1800   # opcional, default 30min
```

Requer a extensão Redis ativa (`KC_COMMUNITY_REDIS_CACHE_ENABLED=true`) e `KC_CACHE=local`.

## O que é cacheado

| Lookup | Chave relativa |
|--------|----------------|
| user by username | `entity:user-by-username:<realmId>:<username>` → userId |
| user by email | `entity:user-by-email:<realmId>:<email>` → userId |
| realm by name | `entity:realm-by-name:<name>` → realmId |
| client by clientId | `entity:client-by-clientId:<realmId>:<clientId>` → client UUID |

O modelo completo continua vindo do JPA (`UserStorageManager` / providers `jpa`). O Redis só acelera a resolução id.

## Invalidação

- `UserCache.evict` / `clear` e `CacheRealmProvider.register*Invalidation` / `clear` limpam L1+L2 e publicam no canal `entity:invalidation`.
- Cada nó mantém L1 em memória e escuta o PUBSUB.

## SPI

| Factory | id | order |
|---------|-----|-------|
| `RedisUserCacheProviderFactory` | `default` | 2 |
| `RedisCacheRealmProviderFactory` | `default` | 2 |

Só são `isSupported` quando `KC_CACHE_REDIS_ENTITY_ENABLED=true`.

## Limitações do MVP

- Não espelha o grafo completo de roles/groups/client-scopes do Infinispan.
- Não substitui revisões finas do `UserCacheSession` / `RealmCacheSession`.
- Com a flag desligada, o Keycloak usa os caches locais Infinispan de entidade (comportamento anterior).
