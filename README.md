# keycloak-cache-redis

Extensão para Keycloak **26.7.1** que substitui caches distribuídos de sessão (Infinispan) por **Redis/Valkey**, via SPI
(`DatastoreProvider` + providers `infinispan`), usando **Lettuce**. Inclui cache-aside para Authorization Services.

**Regiões no Redis:** `userSessions`, `authenticationSessions`, `loginFailures`, `singleUseObjects`.

**Multi-nó:** `ClusterProvider` via Redis PUBSUB (invalidação de caches locais entre nós no mesmo Redis).

Documentação completa: [docs/](docs/README.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

## Build

```bash
./mvnw clean package
# or: mvn clean package
```

Artefato instalável:

```
target/keycloak-cache-redis-1.0-SNAPSHOT-withdeps.jar
```

Copie para `providers/` do Keycloak.

## Configuração

| Variável                                      | Descrição                                             | Exemplo      |
|-----------------------------------------------|-------------------------------------------------------|--------------|
| `KC_COMMUNITY_REDIS_CACHE_ENABLED`            | Liga a extensão (obrigatório)                         | `true`       |
| `KC_CACHE`                                    | Deve ser `local`                                      | `local`      |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MODE`        | `standalone` \| `sentinel` \| `cluster`               | `standalone` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_NODES`       | `host:port` separados por vírgula                     | `redis:6379` |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_MASTER_NAME` | Obrigatório em modo `sentinel`                        | `mymaster`   |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_SSL`         | TLS                                                   | `false`      |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_SSL_VERIFY_PEER` | Verificar certificado TLS (com SSL)               | `true`       |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_USERNAME`    | Usuário Redis (opcional)                              |              |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_PASSWORD`    | Senha Redis (opcional)                                |              |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_TIMEOUT`     | `2000`, `2s`, `500ms`                                 | `2000ms`     |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_DATABASE`    | Índice lógico (standalone/sentinel)                   | `0`          |
| `KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX`  | Prefixo de todas as chaves Redis                      | `kc:`        |
| `KC_REDIS_KEY_PREFIX`                         | Alias de env para o prefixo (se o SPI não for setado) | `kc:`        |

Com prefixo `kc`, as chaves ficam como `kc:user-session:<id>`, `kc:auth-session:<id>`, `kc:cluster:events`, etc.

### Authorization Services (cache Redis)

O cache de Authorization Services usa cache-aside sobre o store JPA (a fonte da verdade permanece no banco). Todas as 5
stores são cacheadas: `Resource` (findById/findByName), `ResourceServer` (findById/findByClient), `Scope`
(findById/findByName), `Policy` (findById/findByResource), e `PermissionTicket` (findById, TTL curto). Invalidação por
geração (`INCR` em `kc:authz:rs-gen:<rsId>`). Falhas de Redis são tratadas como cache miss (não-fatal). Opcionalmente,
habilita LRU local por nó + invalidação cross-node via PUBSUB no canal `kc:authz:invalidation`.

| Variável                                             | Descrição                     | Default           |
|------------------------------------------------------|-------------------------------|-------------------|
| `KC_CACHE_REDIS_AUTHZ_ENABLED`                       | Liga/desliga o cache de authz | `true`            |
| `KC_CACHE_REDIS_AUTHZ_TTL_SECONDS`                   | TTL das entries de cache      | `1800` (30 min)   |
| `KC_CACHE_REDIS_AUTHZ_PERMISSION_TICKET_TTL_SECONDS` | TTL de permission tickets     | `300` (5 min)     |
| `KC_CACHE_REDIS_AUTHZ_GEN_TTL_SECONDS`               | TTL da chave de geração       | `604800` (7 dias) |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_ENABLED`             | Liga LRU local por nó         | `false`           |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_MAX_SIZE`            | Tamanho máximo do LRU local   | `1000`            |
| `KC_CACHE_REDIS_AUTHZ_LOCAL_LRU_TTL_SECONDS`         | TTL local por entry (LRU)     | `30`              |

Detalhes: [docs/authorization.md](docs/authorization.md).

### Modos de conexão

- **standalone** — um nó Redis/Valkey (padrão).
- **sentinel** — lista de sentinels em `NODES` + `MASTER_NAME`.
- **cluster** — lista de seed nodes em `NODES`. Em cluster, `MULTI/EXEC` de índices secundários é desativado (comandos
  individuais; consistência eventual dos índices).

## Smoke local (Docker)

```bash
mvn clean package -DskipTests
docker compose up
```

- Keycloak: http://localhost:8080 (`admin` / `admin`)
- Valkey: `localhost:6379`

Validar: login/logout, refresh token, sessão offline, Admin Console e persistência após
`docker compose restart keycloak`.

### Multi-node (2 Keycloak + Valkey)

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.multinode.yml up
```

- Nó 1: http://localhost:8080
- Nó 2: http://localhost:8081

Sessões ficam no Redis (fonte da verdade). Sticky session **não** é necessária. Invalidação de realm/user/client entre
nós usa o canal PUBSUB `kc:cluster:events`. Detalhes: [docs/clustering.md](docs/clustering.md).

### Sentinel (exemplo)

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
./mvnw test
# opcional: Redis externo (requer REDIS_TEST_ALLOW_FLUSH=true para limpar o DB)
REDIS_TEST_URI=redis://127.0.0.1:6380 REDIS_TEST_ALLOW_FLUSH=true ./mvnw test
```

CI: [GitHub Actions](.github/workflows/ci.yml) e [Woodpecker](.woodpecker/ci.yml).  
Release (Woodpecker → GitHub Releases): [docs/release.md](docs/release.md).  
Changelog: [CHANGELOG.md](CHANGELOG.md).

## Limitações

Resumo; detalhes em [docs/limitations.md](docs/limitations.md).

- Authorization Services: cache Redis cache-aside ativo; desative com `KC_CACHE_REDIS_AUTHZ_ENABLED=false`.
- Sem migração Infinispan → Redis (`importUserSessions` é no-op); após o switchover os usuários reautenticam.
- Modo Redis `cluster`: CAS+índices atômicos só quando as chaves compartilham slot (hash-tags); senão fallback best-effort.
- Sticky session: desnecessária (shim `DisabledStickySessionEncoderProvider`).
