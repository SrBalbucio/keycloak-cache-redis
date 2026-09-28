# Matriz de eventos do cluster bus (Keycloak 26.7.4, `KC_CACHE=local`)

Levantamento da Fase 0: quem emite cada `eventKey` no `cluster:events` (canal Redis
`kc:cluster:events`), quem escuta, e qual cache local Infinispan é invalidado.
Fonte: `keycloak-model-infinispan-26.7.4-sources.jar` + `ClusterEventSerializer` deste repo.

Nosso `RedisPubsubClusterProvider` é transporte transparente: todo `notify()` do core cai no
nosso `PUBLISH`, todo `registerListener()` do core cai no nosso mapa e é despachado no
`handleMessage`. O listener do core (ex.: `RealmCacheManager::onInvalidateEvent`) é quem
evicta/bumpa a revisão no Infinispan local do nó remoto.

## Caches locais (não replicados com `KC_CACHE=local`)

De `InfinispanConnectionProvider`: `realms`, `realmRevisions`, `users`, `userRevisions`,
`authorization`, `authorizationRevisions`, `keys`, `crl`.
Nosso caso: `realms`/`users` são stock 100% local; `authorization` stock foi substituído pelo
nosso cache-aside Redis; `keys` stock foi substituído pelo nosso L2 Redis + L1.

## `REALM_INVALIDATION_EVENTS` / `REALM_CLEAR_CACHE_EVENTS`

Constantes em `InfinispanCacheRealmProviderFactory` (`"REALM_INVALIDATION_EVENTS"`,
`"REALM_CLEAR_CACHE_EVENTS"`, id `default`).

| Papel | Classe / método |
|-------|-----------------|
| Listener invalidação | `InfinispanCacheRealmProviderFactory.lazyInit` → `cluster.registerListener(REALM_INVALIDATION_EVENTS, realmCache::onInvalidateEvent)` (`RealmCacheManager` sobre `realms` + `realmRevisions`) |
| Listener clear | `...registerListener(REALM_CLEAR_CACHE_EVENTS, realmCache::onClearEvent)` |
| Listener sistêmico | `DefaultInfinispanConnectionProviderFactory.registerSystemWideListeners` → `REALM_CLEAR_CACHE_EVENTS` → `sessionFactory.invalidate(null, _ALL_)`; `REALM_INVALIDATION_EVENTS` → `invalidate(null, REALM, id)` para `RealmUpdatedEvent`/`RealmRemovedEvent` |
| Listener authz | `InfinispanCacheStoreFactoryProviderFactory` → `REALM_CLEAR_CACHE_EVENTS` → `storeCache::onClearEvent` (stock; não ativo aqui — nosso authz usa geração + `authz:invalidation`) |
| Emissor | `RealmCacheSession` (ex.: `cluster.notify(REALM_CLEAR_CACHE_EVENTS, ClearCacheEvent, false)`) |
| Eventos transportados | `InvalidationEvent` (base), `RealmUpdatedEvent`, `RealmRemovedEvent`, `ClientAdded/Removed/UpdatedEvent`, `ClientScopeAdded/RemovedEvent`, `RoleAdded/Removed/UpdatedEvent`, `GroupAdded/Moved/Removed/UpdatedEvent`, `CacheKeyInvalidatedEvent` |

## `USER_INVALIDATION_EVENTS` / `USER_CLEAR_CACHE_EVENTS`

Constantes em `InfinispanUserCacheProviderFactory` (`"USER_INVALIDATION_EVENTS"`,
`"USER_CLEAR_CACHE_EVENTS"`, id `default`).

| Papel | Classe / método |
|-------|-----------------|
| Listener invalidação | `InfinispanUserCacheProviderFactory.lazyInit` → `USER_INVALIDATION_EVENTS` → `UserCacheManager::onInvalidateEvent` (`users` + `userRevisions`) |
| Listener clear | `...USER_CLEAR_CACHE_EVENTS` → `UserCacheManager::onClearEvent` |
| Emissor | `UserCacheSession` (`notify(USER_CLEAR_CACHE_EVENTS, ClearCacheEvent, true)`), `RealmAdapter` (`notify(USER_CLEAR_CACHE_EVENTS, ...)` ao mudar realm afetando users) |
| Eventos transportados | `UserUpdatedEvent`, `UserFullInvalidationEvent`, `UserCacheRealmInvalidationEvent`, `UserConsentsUpdatedEvent`, `UserFederationLinkUpdated/RemovedEvent` |

## `AUTHORIZATION_*` (stock — referência, não ativo com nosso authz)

`AUTHORIZATION_INVALIDATION_EVENTS` / `AUTHORIZATION_CLEAR_CACHE_EVENTS` em
`InfinispanCacheStoreFactoryProviderFactory` → `StoreFactoryCacheManager` sobre
`authorization` + `authorizationRevisions` (+ `REALM_CLEAR_CACHE_EVENTS` → clear).
Nosso `RedisCachedStoreProviderFactory` (id `default`, order 2) substitui esse SPI; usamos
geração `INCR` + TTL em vez de revisão. Serializamos os mesmos tipos de evento por
compatibilidade, mas o emissor stock não roda aqui.

## Sessões (referência — 100% Redis neste projeto)

- `REALM_REMOVED_EVENT_SESSIONS` / `REMOVE_USER_SESSIONS_EVENT`
  (`InfinispanUserSessionProviderFactory:235-249`), `REALM_REMOVED_EVENT_AUTHSESSIONS` /
  `AUTHENTICATION_SESSION_EVENTS` (`InfinispanAuthenticationSessionProviderFactory:121-129`),
  `REALM_REMOVED_SESSION_EVENT` / `REMOVE_ALL_LOGIN_FAILURES_EVENT`
  (`InfinispanUserLoginFailureProviderFactory:89-100`).
- Stock Infinispan dessas regiões **não é usado** com o flag ligado; mantemos
  `AuthenticationSessionAuthNoteUpdateEvent` no serializer por compatibilidade, sem emissor ativo.

## `keys` / `crl` (referência — nosso provider substitui keys)

- Stock: `PUBLIC_KEY_STORAGE_INVALIDATION_EVENT` / `KEYS_CLEAR_CACHE_EVENTS`
  (`InfinispanCachePublicKeyProviderFactory:54-65`, cache `keys`, max-idle 3600s).
- Aqui: `RedisPublicKeyStorageProvider` usa Redis L2 + L1 com canal próprio
  `public-keys:invalidation` (`docs/clustering.md`).

## Cobertura do serializer vs emissores ativos

`ClusterEventSerializer` cobre todos os tipos acima (round-trip em
`ClusterEventSerializerTest`). Com o flag ligado, os emissores ativos são os de
**realm/user** (stock local) — sessão/authz/keys stock não emitem porque seus SPIs foram
substituídos. `DCNotify` é transportado no envelope mas sem filtro multi-DC (single-site;
`docs/limitations.md`).

## Implicação para Fase 1

- Teste parametrizado deve cobrir **todos** os tipos (hoje só `ClientAddedEvent` no
  `RedisPubsubClusterProviderIntegrationTest`), mas o SLO operacional mira
  `REALM_*` + `USER_*` — são os únicos com emissor stock ativo neste modo.
- `dropped_no_listener` por `eventKey` (nova métrica Fase 0) distingue "nó sem aquele cache"
  de "perda real": ex. `AUTHORIZATION_*` sem listener é esperado aqui, `REALM_*` sem listener não é.
