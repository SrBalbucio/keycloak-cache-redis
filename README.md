# keycloak-cache-redis

Extensão para Keycloak **26.7.1** que substitui o cache distribuído de **user sessions** (Infinispan) por **Redis/Valkey**, via SPI (`DatastoreProvider` + `UserSessionProvider`), usando **Lettuce**.

> MVP (Fase 0 + 1): apenas `userSessions`. Authentication sessions, login failures e single-use objects ainda usam Infinispan local (`KC_CACHE=local`).

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
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MODE` | Só `standalone` no MVP | `standalone` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_NODES` | `host:port` | `redis:6379` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_SSL` | TLS | `false` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_USERNAME` | Usuário Redis (opcional) | |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_PASSWORD` | Senha Redis (opcional) | |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_TIMEOUT` | `2000`, `2s`, `500ms` | `2000ms` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_DATABASE` | Índice lógico | `0` |

## Smoke local (Docker)

```bash
mvn clean package -DskipTests
docker compose up
```

- Keycloak: http://localhost:8080 (`admin` / `admin`)
- Valkey: `localhost:6379`

Validar: login/logout, refresh token, sessão offline, sessões no Admin Console e persistência após restart do container Keycloak (`docker compose restart keycloak`). Chaves esperadas no Redis: `user-session:*`, `authenticated-client:*`.

## Testes

```bash
mvn test
# opcional: Redis já rodando (ex.: docker run -p 6380:6379 redis:7.2-alpine)
REDIS_TEST_URI=redis://127.0.0.1:6380 mvn test
```

Inclui testes de `MapEntity` e `RedisHashCas` (Testcontainers ou `REDIS_TEST_URI`).

## Escopo futuro

- Fase 2: `authenticationSessions`
- Fase 3: `loginFailures` + `singleUseObjects`
- Fase 4: `ClusterProvider` (PUBSUB), modos sentinel/cluster, métricas

Detalhes de arquitetura: [docs/SPEC.md](docs/SPEC.md).
