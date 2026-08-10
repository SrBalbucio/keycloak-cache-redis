# Spec — Correções de login browser e melhorias próximas

Estado: **Defeito A corrigido**; **Defeito B** com workaround aplicado e fix definitivo a decidir.

## Escopo

Dois defeitos independentes que impediam um login browser completo com o SPI ativo,
mais a auditoria de padrões similares nos demais adapters.

| # | Defeito | Subsistema | Estado |
|---|---------|-----------|--------|
| A | NPE em `getProtocol()` no redirect de sucesso | auth-session | Corrigido |
| B | `ClassCastException`/NPE ao listar IDPs/Organizations | realm/user cache | **Resolvido (B1)** |

---

## Defeito A — Auth-session adapter: leitura após remoção da tab

### Sintoma
No redirect de sucesso do login:
```
AuthenticationManager.redirectAfterSuccessfulFlow:943
  -> DefaultKeycloakSession.getProvider:194 (List.of(name, null) -> NPE)
```
(seguido de um NPE secundário em `FreeMarkerLoginFormsProvider.prepareBaseUriBuilder:406`
ao renderizar a página de erro). O campo `protocol` **está** no Redis, mas `getProtocol()`
devolvia `null`.

### Causa raiz
No caminho de sucesso, em uma **mesma referência de `authSession`** e na **mesma request**:

1. `TokenManager.attachAuthenticationSession:486`
   -> `AuthenticationSessionManager.updateAuthenticationSessionAfterSuccessfulAuthentication:257`
   -> `removeTabIdInAuthenticationSession:240`
   -> `root.removeAuthenticationSessionByTabId(tabId)`.
   No SPI, isso executa `removeTabFields(tabId)` e apaga **todos** os campos `t.<tabId>.*`
   do root entity, **incluindo `protocol`**.
2. `AuthenticationManager.redirectAfterSuccessfulFlow:943` chama `authSession.getProtocol()`.

O adapter stock (`org.keycloak.models.sessions.infinispan.AuthenticationSessionAdapter`)
segura uma **entity desacoplada** (`updater.getEntity().getProtocol()`) — sobrevive à
remoção da tab. O `RedisAuthenticationSessionAdapter` era uma **view viva**
(`parent.getTabField(tabId, "protocol")` lendo direto do root entity compartilhado); ao
remover a tab, o getter devolvia `null` -> `List.of(clazz.getName(), null)` -> NPE.

### Correção aplicada
`RedisAuthenticationSessionAdapter` agora é **snapshot + write-through**:

- `fields` (suffix -> value) capturado no constructor via `parent.getTabMap(tabId, "")`.
- Getters leem do snapshot.
- Setters atualizam o snapshot **e** o parent (`setTabField`/putMap/clearMap) para persistência.
- O adapter continua legível depois de `removeAuthenticationSessionByTabId`, igual ao stock.

**Arquivo:** `src/main/java/balbucio/keycloak/cache/redis/authSession/RedisAuthenticationSessionAdapter.java`.

**Testes de regressão** (`RedisAuthenticationSessionProviderIntegrationTest`):
- `removedTabAdapterStillExposesFields`
- `writesBeforeTabRemovalArePersisted`

**Memória:** `gotchas/auth-session-adapter-must-survive-tab-removal.md`.

### Riscos residuais
- O snapshot é capturado na construção; mutações externas à tab feitas direto no root
  (`restartSession`, eviction) não são refletidas num adapter já construído. É o mesmo
  comportamento do stock (entity desacoplada) e não ocorre no fluxo normal de login.

---

## Defeito B — Realm/user cache: cast incompatível com providers core

### Sintoma
Página de login não abre:
```
ClassCastException: jdk.proxy2.$Proxy85 cannot be cast to
  org.keycloak.models.cache.infinispan.RealmCacheSession
  at InfinispanOrganizationProvider.<init>:58
  at InfinispanIdentityProviderStorageProvider.<init>:61
```

### Causa raiz
Providers core do Keycloak tratam o `CacheRealmProvider`/`UserCache` como sendo
**concretamente** os tipos Infinispan:
```java
// InfinispanIdentityProviderStorageProvider / InfinispanOrganizationProvider
this.realmCache = (RealmCacheSession) session.getProvider(CacheRealmProvider.class);
this.userCache  = (UserCacheSession)  session.getProvider(UserCache.class);
... realmCache.getCache().getCurrentRevision(id) ...   // RealmCacheManager (Infinispan)
... realmCache.getCache().addRevisioned(cached, ...) ...
```
Não é só cast: eles **dependem do `RealmCacheManager`/`UserCacheManager`**
(revisionamento Infinispan). O SPI provê:

- `RedisCacheRealmProvider` -> `Proxy` que só implementa `CacheRealmProvider`.
- `RedisUserCache extends UserStorageManager implements UserCache`.

Nenhum dos dois é (nem um Proxy pode ser) `RealmCacheSession`/`UserCacheSession`, que são
**classes concretas**. O cast sempre falha quando o entity-cache está ativo.

### Workaround
`KC_CACHE_REDIS_ENTITY_ENABLED=false` desliga **só** o realm cache e o user cache do SPI
(ambos gated por esse flag). Os providers core resolvem `CacheRealmProvider`/`UserCache`
stock (Infinispan local) e os casts passam. **Permanecem no Redis:** userSession,
authSession (com fix A), loginFailure, singleUse, pubsub, authz, publicKey e o
datastore-routing.

### Mecanismo real (sobrescrita do slot `default`)

A investigação no container revelou que o recurso quebrava **nos dois estados**:

- As factories do SPI (`RedisCacheRealmProviderFactory`, `RedisUserCacheProviderFactory`)
  usavam `id="default"` + `order=2` (`Constants.DEFAULT_PROVIDER_ID`/`PROVIDER_PRIORITY`), a
  mesma `id` da stock `InfinispanCacheRealmProviderFactory` (ordem menor).
- No registro, **mesmo id + ordem maior sobrescreve a stock** no slot `default` do SPI
  `cache-realm` (e `user-cache`). Elas não coexistem.
- Flag **ligada**: a factory do SPI vira o default → `create()` devolve o Proxy →
  `ClassCastException` nos casts para `RealmCacheSession`/`UserCacheSession`.
- Flag **desligada**: `isSupported=false` filtra a factory do SPI no runtime → o slot
  `default` fica **vazio** (a stock foi sobrescrita) → `getProvider(CacheRealmProvider.class)`
  devolve `null` → `NullPointerException` em `InfinispanIdentityProviderStorageProvider.<init>:62`.

Nota: `cache-realm` é um SPI **público**, então a factory do SPI **não** dispara
`KC-SERVICES0047` no log de build — a ausência do aviso não significa que a factory está fora.

### Decisão: B1 (remover) — aplicado

O realm/user cache Redis foi **removido** da extensão. Foram apagados:

- `entity/RedisCacheRealmProviderFactory.java`
- `entity/RedisUserCacheProviderFactory.java`
- `entity/RedisCacheRealmProvider.java`
- `entity/RedisUserCache.java`
- `entity/EntityCacheConfig.java`

Mantido `entity/RedisEntityIndexCache.java` (utilidade cache-aside genérica, com teste próprio).
Realm/user cache voltam ao Infinispan local (stock) — compatível com os casts do core. As
variáveis `KC_CACHE_REDIS_ENTITY_ENABLED`/`KC_CACHE_REDIS_ENTITY_TTL_SECONDS` não têm mais
efeito.

### Por que não B2

B2 (subclasses de `RealmCacheSession`/`UserCacheSession`) resolveria o cast quando ligido,
mas **não** resolveria a sobrescrita do slot `default` quando desligado — exigiria também
mudar o `id` para não colidir com a stock. Além disso, acoplaria o SPI aos internals do
Infinispan (`RealmCacheManager`/`UserCacheManager`, `InfinispanConnectionProvider`) e **não é
validável** no harness de testes atual (que não sobe `InfinispanConnectionProvider`). O
ganho funcional (índice name→id no Redis) é marginal frente ao cache Infinispan local.
Custo/benefício ruim.

---

## Auditoria dos demais adapters (padrão do Defeito A)

Objetivo: verificar se outros adapters são "live views" sobre uma entity compartilhada e
se algum fluxo core lê campos depois de remover a entity na mesma request.

| Adapter | Estrutura | Leitura após remoção | Risco |
|---|---|---|---|
| `RedisAuthenticationSessionAdapter` | **era live view** -> agora snapshot | `redirectAfterSuccessfulFlow` lê `getProtocol()` pós-remoção (garantido pelo core) | **Corrigido** |
| `RedisRootAuthenticationSessionAdapter` | `extends MapEntity` (container das tabs) | n/a (é o próprio root) | Nenhum |
| `RedisUserSessionAdapter` | `extends MapEntity` (own data) + `check()` | possível em logout/backchannel | Latente (ver abaixo) |
| `RedisAuthenticatedClientSessionAdapter` | `extends MapEntity` (own data) + `check()` | possível após `detachFromUserSession` | Latente (ver abaixo) |
| `RedisUserLoginFailureAdapter` | `extends MapEntity` (own data), sem `check()` | raro (failures não são lidas pós-remoção) | Baixo |

### Conclusão da auditoria
1. **O auth-session era o único adapter live-view** — a única origem do bug silencioso de
   "null pós-remoção". Já corrigido.
2. Os adapters de user/client/loginFailure **já seguram sua própria `MapEntity`**
   (snapshot de dados) — **não** têm o bug do null silencioso.
3. **Risco residual (não agudo):** `RedisUserSessionAdapter` e `RedisAuthenticatedClientSessionAdapter`
   têm `check()` que lança `ModelIllegalStateException` se a entity está marcada para deleção.
   Se algum fluxo core (logout backchannel, `detachFromUserSession`) ler o adapter depois do
   provider marcar deleção, lança exceção. É fail-fast (melhor que null silencioso), mas pode
   aparecer em fluxos de borda.

### Follow-up recomendado
- Teste de regressão de logout backchannel e `detachFromUserSession` para confirmar se o
  `check()` é acionado. Se for, alinhar o comportamento ao stock (que retorna a entity
  desacoplada sem lançar) — por exemplo, não relançar em getters somente-leitura após delete,
  ou capturar snapshot de leitura.

---

## Plano de teste

| Cenário | Tipo | Status |
|---|---|---|
| `removedTabAdapterStillExposesFields` | integração | Novo, passando |
| `writesBeforeTabRemovalArePersisted` | integração | Novo, passando |
| Suíte completa (141 testes) | unit + integração | Verde |
| Login browser no realm master (manual) | manual | Pendente (entity-cache off) |
| Login com identity provider configurado (manual) | manual | Pendente (valida workaround B) |
| Logout backchannel / detachFromUserSession | integração | A criar (follow-up da auditoria) |

---

## Sequenciamento

1. **Agora:** validar workaround B (`KC_CACHE_REDIS_ENTITY_ENABLED=false`) — confirmar login
   ponta-a-ponta sem NPE e sem CCE.
2. **Próximo commit:** manter fix A; decidir B1 vs B2 (sugestão: B1 + atualizar
   `docs/entity-cache.md` e `docs/limitations.md`).
3. **Follow-up:** teste de logout (item 3 do plano) e, se necessário, ajustar `check()`
   nos adapters de user/client session.

---

## Referências do Keycloak (26.7.1)

- `TokenManager.attachAuthenticationSession:486`
- `AuthenticationSessionManager.updateAuthenticationSessionAfterSuccessfulAuthentication:257`
- `AuthenticationSessionManager.removeTabIdInAuthenticationSession:240`
- `AuthenticationManager.redirectAfterSuccessfulFlow:943`
- `DefaultKeycloakSession.getProvider:194` (`List.of(clazz.getName(), id)` -> NPE se id null)
- `InfinispanIdentityProviderStorageProvider.<init>:61` (cast -> `RealmCacheSession`)
- `InfinispanOrganizationProvider.<init>:58` (cast -> `RealmCacheSession`) e `:59` (-> `UserCacheSession`)
- Stock: `RealmCacheSession(RealmCacheManager, KeycloakSession)`, `UserCacheSession(UserCacheManager, KeycloakSession)`
