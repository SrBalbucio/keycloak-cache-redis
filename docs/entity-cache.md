# Entity cache (realm/user) — removido

O cache Redis de realm/user (índices de `getRealmByName`, `getClientByClientId`, lookup de
user por username/email) foi **removido** da extensão. As variáveis de ambiente
`KC_CACHE_REDIS_ENTITY_ENABLED` / `KC_CACHE_REDIS_ENTITY_TTL_SECONDS` **não têm mais efeito**.

## Por que foi removido

Dois problemas estruturais tornavam o recurso irrecuperável sem acoplar o SPI aos internals
do Infinispan:

1. **Cast incompatível (flag ligada).** Providers core do Keycloak 26.x fazem cast hard-coded
   do `CacheRealmProvider`/`UserCache` para `RealmCacheSession`/`UserCacheSession` (classes
   Infinispan) e usam o `RealmCacheManager`/`UserCacheManager` para revisionamento:
   - `InfinispanIdentityProviderStorageProvider.<init>`
   - `InfinispanOrganizationProvider.<init>`

   As implementações do SPI (`RedisCacheRealmProvider` via `Proxy`; `RedisUserCache extends
   UserStorageManager`) não podiam ser cast para essas classes concretas → `ClassCastException`
   ao renderizar a página de login.

2. **Sobrescrita do slot `default` (flag desligada).** As factories do SPI usavam
   `id="default"` + `order=2`, a mesma `id` da stock `InfinispanCacheRealmProviderFactory`
   (ordem menor). No registro, **mesmo id + ordem maior sobrescreve a stock** no slot
   `default`. Com `isSupported=false` no runtime, a factory do SPI era filtrada e o slot
   `default` ficava **vazio** (a stock não coexistia) → `getProvider(CacheRealmProvider.class)`
   devolvia `null` → `NullPointerException` em `InfinispanIdentityProviderStorageProvider`.

Ou seja, o recurso quebrava **nos dois estados** (ligado e desligado).

## Estado atual

Realm cache e user cache voltam ao Infinispan stock (local, em `KC_CACHE=local`), que é
compatível com os casts do core. As caches de **sessão** (userSession, authSession,
loginFailure, singleUse), pubsub, authz e publicKey continuam no Redis normalmente.

## Notas de diagnóstico (como o defeito se manifestava)

- Flag **ligada**: `ClassCastException: jdk.proxy2.$Proxy85 cannot be cast to
  org.keycloak.models.cache.infinispan.RealmCacheSession` em
  `InfinispanOrganizationProvider.<init>:58` e `InfinispanIdentityProviderStorageProvider.<init>:61`.
  O core depende ainda do `RealmCacheManager`/`UserCacheManager` (revisionamento), não só do cast.
- Flag **desligada** (quando as factories ainda existiam com `id="default"`): o slot `default`
  ficava vazio após `isSupported=false` filtrar a factory do SPI →
  `getProvider(CacheRealmProvider.class)` devolvia `null` → `NullPointerException` em
  `InfinispanIdentityProviderStorageProvider.<init>:62`.
- Armadilha: `cache-realm` é um SPI **público**, então a factory do SPI **não** dispara
  `KC-SERVICES0047` no log de build — a ausência do aviso não significa que a factory está fora.
- Validação manual relevante: login com identity provider configurado exercita os casts acima;
  smoke em [Instalação](installation.md) / [README](../README.md).

## Se um dia quiser realm/user cache no Redis

Não basta usar `id="default"`: é preciso (a) registrar com `id` distinto (ex.: `redis`) para
não sobrescrever a stock, e (b) fazer o provider **estender** `RealmCacheSession`/`UserCacheSession`
reusando o `RealmCacheManager`/`UserCacheManager` real (presente mesmo em `KC_CACHE=local`).
Isso acopla o SPI aos internals do Infinispan — custo/benefício ruim frente ao cache local, que
já é eficiente. Detalhe adicional: B2 (subclasses) resolveria o cast com flag ligada, mas
**não** a sobrescrita do slot `default` com flag desligada (exigiria o `id` distinto de qualquer
forma), e não é validável no harness atual (sem `InfinispanConnectionProvider`).

## Utilidade reaproveitável

`balbucio.keycloak.cache.redis.entity.RedisEntityIndexCache` (índice cache-aside genérico com
L1+L2 e invalidação PUBSUB) permanece no código e tem teste próprio; pode ser reusado para
outros fins no futuro.
