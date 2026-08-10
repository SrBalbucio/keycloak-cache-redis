# Entity cache MVP (Redis)

Cache-aside de **índices de lookup** para user/realm/client, opcional e desligado por padrão.

> **⚠️ Incompatível com o core do Keycloak — mantenha desligado (`KC_CACHE_REDIS_ENTITY_ENABLED=false`).**
>
> Em Keycloak 26.x, providers core como `InfinispanIdentityProviderStorageProvider` e
> `InfinispanOrganizationProvider` fazem cast hard-coded do `CacheRealmProvider`/`UserCache`
> para `RealmCacheSession`/`UserCacheSession` (classes Infinispan) e usam o
> `RealmCacheManager`/`UserCacheManager` para revisionamento. Os providers do SPI
> (`RedisCacheRealmProvider` é um `Proxy`; `RedisUserCache extends UserStorageManager`)
> não são (nem podem ser) essas classes, logo o cast lança `ClassCastException` ao renderizar
> a página de login (listagem de IDPs/Organizations). Veja
> `docs/spec-authsession-and-realm-cache-fix.md` (Defeito B).
>
> **Workaround:** deixe o entity-cache desligado; realm/user cache continuam no Infinispan
> local. As caches de sessão (userSession, authSession, loginFailure, singleUse), pubsub,
> authz e publicKey continuam no Redis normalmente.

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

- **Incompatível com providers core do Keycloak 26.x** (IDP, Organizations) — veja o aviso
  no topo e o Defeito B em `docs/spec-authsession-and-realm-cache-fix.md`. Por isso deve
  permanecer **desligado** até um reescrita (opção B2 da spec: `RedisCacheRealmProvider
  extends RealmCacheSession` e `RedisUserCache extends UserCacheSession`).
- Não espelha o grafo completo de roles/groups/client-scopes do Infinispan.
- Não substitui revisões finas do `UserCacheSession` / `RealmCacheSession`.
- Com a flag desligada, o Keycloak usa os caches locais Infinispan de entidade (comportamento anterior).
