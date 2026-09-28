# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-09-28

Public `redisCache` SPI for other Keycloak extensions (`RedisCacheProvider`, factory id
`redis`): namespaced String/JSON cache with per-entry TTL, cache-aside `getOrLoad`,
optional node-local LRU with cross-node PUBSUB invalidation (+ reconnect resync),
fail-open reads, type-guarded gets. See `docs/cache-api.md`.

## [2.0.0] - 2026-09-28

Alvo: Keycloak 26.7.x. Baseline multinó validado (delivery 1:1, lag em ms, recovery sem
restart) — ver `docs/cluster-baseline.md`.

### Added

- Observabilidade do cluster bus: `vendor.lettuce.cluster.events` (por `eventKey`: `sent`,
  `delivered`, `self_ignored`, `dropped_*`, `deser_error`, `publish_error`, `reconnected`,
  `resync_cleared`), `vendor.lettuce.cluster.lag` (publish→deliver) e
  `vendor.lettuce.cluster.task` (`finished`/`timeout`); `sentAtMillis` no envelope.
- Reconciliação no reconnect PUBSUB (`PubSubReconnect`): limpa L1 próprio (authz LRU,
  public-keys L1); canal `cluster:events` só observa (sem L1).
- Espera fatiada em `executeIfNotExecutedAsync`: desiste antes do teto quando o lock some
  sem conclusão (teto total inalterado).
- Tolerância multi-versão no serializer (`FAIL_ON_UNKNOWN_PROPERTIES=false`; tipos fora da
  allowlist continuam rejeitados) + suporte a `UserVerifiableCredentialsUpdatedEvent`.
- Cobertura de transporte parametrizada (23 tipos de evento inter-nó) e `warn` em
  `DCNotify` não-`ALL_DCS` (single-site assumido).
- Conexão Redis lazy: `init` só valida config; boot nunca morre por Redis inalcançável.
- `createClientSession` add-if-absent como o stock: segundo create não sobrescreve a entity
  viva nem conta stats de novo.
- Topologia multinó de referência: Postgres compartilhado + LB nginx (`:8090`, sem sticky)
  + métricas por nó (`docs/cluster-baseline.md`, `docker-compose.multinode.yml`).
- Matriz `eventKey` → emissor/listener → cache local (`docs/cluster-event-matrix.md`).
- Testes: guard de lifecycle (`close()` no-op), reconnect, waiter early-exit, lazy connect,
  add-if-absent, chaos (lost-invalidation, disconnect).

### Fixed

- **Crítico:** `DefaultKeycloakSession.close()` (inclusive no bootstrap) fechava o
  `ClusterProvider` compartilhado e matava a invalidação cross-node sem logs. `close()` agora
  é no-op como o stock; ciclo de vida na factory.
- `RedisPublicKeyStorageProvider.close()` limpava o L1 compartilhado a cada request.
- `PubSubReconnect` ignorava o primeiro (e único) reconnect — todo evento agora reconcilia.
- Auth-session adapter snapshot + write-through (leitura pós-remoção de tab).
- `executeIfNotExecutedAsync` completa waiters via `cluster:task-finished`.
- `ResourceAdapter.getScopes()` em cache hit.

### Changed

- Authz cache outcome metrics (`HIT` / `MISS` / `ERROR`) e CAS (`CAS_RETRY` / `CAS_FAIL`).
- Locks async completam via `cluster:task-finished`.
- Hash-tags `{realmId}`, índices ZSET + paginação admin, contadores `getActiveClientSessionStats`.
- Offline JPA write-through/preload opt-in; revoked tokens duráveis (default true).
- Conexão `authz` separada opt-in; public keys em Redis L2+L1+PUBSUB.
- Key layouts com hash-tag (breaking para chaves antigas); `FLUSHDB` externo exige flag.

### Removed

- Entity cache Redis de realm/user (incompatível com casts do core para
  `RealmCacheSession`/`UserCacheSession` + sobrescrita do slot `default`); flags
  `KC_CACHE_REDIS_ENTITY_*` sem efeito. Detalhes em `docs/entity-cache.md`.
- `docs/spec-authsession-and-realm-cache-fix.md` dissolvido nos docs finais.

## [1.0.0] - 2026-08-11

Initial stable release target for Keycloak 26.7.1.
