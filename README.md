# keycloak-cache-redis

Extensão para Keycloak **26.7.1** que substitui caches distribuídos de sessão (Infinispan) por **Redis/Valkey**, via SPI (`DatastoreProvider` + providers `infinispan`), usando **Lettuce**.

**Regiões no Redis:** `userSessions`, `authenticationSessions`, `loginFailures`, `singleUseObjects`.

**Multi-node (mesma região):** `ClusterProvider` via Redis PUBSUB (invalidação de caches locais entre nós).

## Build

```bash
mvn clean package
```

Artefato instalável:

```
target/keycloak-cache-redis-1.0-SNAPSHOT-withdeps.jar
```

Copie para `providers/` do Keycloak.

## Configuração

| Variável | Descrição | Exemplo |
|---|---|---|
| `KC_COMMUNITY_REDIS_CACHE_ENABLED` | Liga a extensão (obrigatório) | `true` |
| `KC_CACHE` | Deve ser `local` | `local` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MODE` | `standalone` \| `sentinel` \| `cluster` | `standalone` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_NODES` | `host:port` separados por vírgula | `redis:6379` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MASTER_NAME` | Obrigatório em modo `sentinel` | `mymaster` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_SSL` | TLS | `false` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_USERNAME` | Usuário Redis (opcional) | |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_PASSWORD` | Senha Redis (opcional) | |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_TIMEOUT` | `2000`, `2s`, `500ms` | `2000ms` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_DATABASE` | Índice lógico (standalone/sentinel) | `0` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX` | Prefixo de todas as chaves Redis | `kc:` |
| `KC_REDIS_KEY_PREFIX` | Alias de env para o prefixo (se o SPI não for setado) | `kc:` |

Com prefixo `kc`, as chaves ficam como `kc:user-session:<id>`, `kc:auth-session:<id>`, `kc:cluster:events`, etc.

### Modos de conexão

- **standalone** — um nó Redis/Valkey (padrão).
- **sentinel** — lista de sentinels em `NODES` + `MASTER_NAME`.
- **cluster** — lista de seed nodes em `NODES`. Em cluster, `MULTI/EXEC` de índices secundários é desativado (comandos individuais; consistência eventual dos índices).

## Smoke local (Docker)

```bash
mvn clean package -DskipTests
docker compose up
```

- Keycloak: http://localhost:8080 (`admin` / `admin`)
- Valkey: `localhost:6379`

Validar: login/logout, refresh token, sessão offline, Admin Console e persistência após `docker compose restart keycloak`.

### Multi-node (2 Keycloak + Valkey)

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.multinode.yml up
```

- Nó 1: http://localhost:8080
- Nó 2: http://localhost:8081

Sessões ficam no Redis (fonte da verdade). Sticky session **não** é necessária. Invalidação de realm/user/client entre nós usa o canal PUBSUB `kc:cluster:events`.

### Sentinel (referência)

```bash
docker compose -f docker-compose.sentinel.yml up
```

Configure Keycloak com `MODE=sentinel`, `NODES=sentinel1:26379,sentinel2:26379` e `MASTER_NAME=mymaster`.

## Métricas

Com métricas do Keycloak habilitadas (`/metrics`), a extensão registra:

- Latência de comandos Lettuce (`MicrometerCommandLatencyRecorder`)
- Contadores `vendor.lettuce.cache` com tags `cache` e `op` (`HGETALL`, `HSETEX`, `SADD`, `DEL`, `EVAL`, `PUBLISH`, …)

## Testes

```bash
mvn test
# opcional: Redis já rodando
REDIS_TEST_URI=redis://127.0.0.1:6380 mvn test
```

## Limitações

- Authorization Services: cache desativado (`NullCachedStoreProviderFactory`); authz fora do escopo.
- Sem migração Infinispan → Redis (`importUserSessions` é no-op); após o switchover os usuários reautenticam.
- Modo `cluster`: índices secundários sem atomicidade `MULTI/EXEC`.
- Multi-region active-active: fora de escopo.
- Sticky session: desnecessária (shim `DisabledStickySessionEncoderProvider`).

Detalhes de arquitetura: [docs/SPEC.md](docs/SPEC.md).
