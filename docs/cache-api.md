# Cache API para outras extensões (`redisCache` SPI)

O SPI público `redisCache` permite que qualquer outra extensão Keycloak use o Redis desta
extensão como cache namespacado — sem gerenciar conexão, prefixo, serialização, TTL ou
invalidação cross-node. Requer a extensão ativa (`KC_COMMUNITY_REDIS_CACHE_ENABLED=true`).

## Uso mínimo

```java
RedisCache cache = session.getProvider(RedisCacheProvider.class).getCache("my-feature");

// escrita com TTL de 5 minutos
cache.put("user:123", dto, 300);

// leitura tipada (miss em ausência, expiração ou tipo divergente — nunca ClassCastException)
MyDto dto = cache.get("user:123", MyDto.class);

// cache-aside: executa o loader uma vez e cacheia o resultado não-nulo
MyDto fresh = cache.getOrLoad("user:123", MyDto.class, 300, () -> loadFromDatabase());

// invalidação precisa (L2 + L1 local + broadcast para o L1 dos outros nós)
cache.remove("user:123");
```

Com config explícita:

```java
RedisCacheConfig config = RedisCacheConfig.defaults()
        .withDefaultTtlSeconds(600)
        .withLruEnabled(true)
        .withLruMaxSize(2000)
        .withLruTtlSeconds(30);
RedisCache cache = session.getProvider(RedisCacheProvider.class).getCache("my-feature", config);
```

## Regras de namespace e chaves

- Namespace: 1–64 caracteres de `[a-z0-9_-]` (ex.: `"billing-plans"`). Tudo é namespacado —
  chaves Redis (`<prefixo>cache-api:<ns>:<key>`), LRU local e canal de invalidação — então
  consumidores nunca enxergam dados uns dos outros.
- Chave: não vazia e sem `*` (reservado para o broadcast de `clear()`).

## Semântica (v1)

| Operação | Comportamento |
|----------|---------------|
| `get` | L1 local → L2 Redis. Miss em ausente, expirado, tipo divergente ou falha de I/O |
| `put` (ttl > 0) | `SET EX` + preenche L1 + broadcast de invalidação da chave |
| `put` (ttl ≤ 0) | Persistente no Redis (sem `EX`); L1 limitado por `lruTtlSeconds` |
| `put` (valor `null`) | Equivale a `remove` |
| `remove` | `DEL` + limpa L1 + broadcast |
| `clear()` | Limpa o L1 em **todos** os nós via broadcast; o L2 **não** é enumerado — entries expiram pelo próprio TTL. Prefira `remove()` para invalidação imediata e precisa |
| `getOrLoad` | Miss → roda o loader; resultado não-nulo é cacheado. Exceção do loader propaga (é lógica do chamador, não falha de cache) |

Tipos: `String` é armazenado raw; qualquer outro tipo como JSON (Jackson). Leituras são
protegidas por tipo — gravou `Widget`, leu como `String` → miss. Classes precisam de
construtor sem args + getters/setters (padrão Jackson); containers genéricos
(`List<Foo>`) não são suportados via `Class<T>` — use um DTO wrapper.

## Confiabilidade

- **Fail-open**: falhas de Redis degradam para miss/no-op e nunca lançam (mesma doutrina do
  cache de authz). Falha de *serialização* no `put` lança `IllegalArgumentException`
  (bug do chamador, fail-fast).
- **Staleness limitado**: `put`/`remove` fazem broadcast imediato; se um broadcast se perder
  (outage), o L1 nunca serve nada além de `lruTtlSeconds` (default 60s) e é limpo no
  reconnect (mesmo mecanismo da Fase 1.2).
- **Sem thundering-herd contratado**: `getOrLoad` concorrente pode rodar o loader 2x;
  assuma loaders idempotentes.

## Observabilidade

Contadores `vendor.lettuce.cache{cache="cacheApi",op=...}`: `HIT`/`MISS`/`ERROR` (leituras),
`SET`/`DEL`/`PUBLISH` (escritas). Sem tag por namespace (cardinalidade sob controle).

## Limites conhecidos (v1)

- Sem listagem/scan por namespace (por isso `clear()` não toca o L2).
- Sem CAS transacional / locks — para coordenação use o `ClusterProvider`.
- Uma conexão PUBSUB por nó compartilhada entre namespaces; LRU por namespace limitado por
  `lruMaxSize` (default 1000).
