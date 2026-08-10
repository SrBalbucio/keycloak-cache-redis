# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `RedisPublicKeyStorageProvider`: Redis L2 + L1 + PUBSUB invalidation for public keys (SPI id `infinispan`).
- Separate `redisConnection` factory `id=authz` (`KC_SPI_REDIS_CONNECTION_AUTHZ_*`) with fallback to `default`.
- Durable revoked tokens: write-through to `RevokedTokenPersisterProvider` + preload on `PostMigrationEvent` (`persistRevokedTokens`, default true).
- Entity cache MVP (`KC_CACHE_REDIS_ENTITY_ENABLED`, default false): Redis L2 indexes for user/realm/client hot paths + L1 + invalidation (`docs/entity-cache.md`).
- Woodpecker CI (`.woodpecker/ci.yml`) and release deploy (`.woodpecker/release.yml` → GitHub Releases on `v*` tags).
- GitHub Actions CI (`mvn verify` with Testcontainers Redis).
- Apache License 2.0 and Maven Wrapper.
- Integration tests for session, auth-session, login-failure, single-use, and cluster providers.
- Atomic Lua CAS + index membership updates (`RedisHashCas` / `RedisChangelogTransaction`).
- Redis Cluster hash-tags for login-failure keys (`{realmId}`).
- Index lifecycle: membership deltas, expire-time cleanup, index TTL refresh.
- Allowlisted Jackson polymorphism for cluster PUBSUB events (no open `DefaultTyping`).
- Lua-based `put` / `replace` for single-use objects.
- Safe cluster lock unlock via compare-and-del Lua.

### Fixed

- `ResourceAdapter.getScopes()` now resolves cached `scopeIds` on cache hit.

### Changed

- Login-failure Redis key layout uses hash-tags (breaking for existing login-failure keys).
- `getActiveClientSessionStats` / client-index streams use pipelined `getAll`.
- External `REDIS_TEST_URI` no longer allows `FLUSHDB` unless `REDIS_TEST_ALLOW_FLUSH=true`.

## [1.0.0] - TBD

Initial stable release target for Keycloak 26.7.1.
