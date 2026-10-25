package balbucio.keycloak.cache.redis.revokedToken;

import java.util.Objects;

import org.keycloak.models.RevokedTokenProvider;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Redis-backed {@code revokedToken} area (KC 26.8 SPI, {@code KeycloakSession.revokedTokens()}).
 *
 * <p>Mirrors the stock {@code InfinispanRevokedTokenProvider}: revoked tokens are stored as
 * single-use-object entries keyed {@code id + REVOKED_KEY}, so they reuse this extension's
 * atomic Lua writes, cross-node consistency and the optional {@code persistRevokedTokens}
 * write-through to JPA plus the startup preload. {@code put} maps to {@code putIfAbsent}
 * (Redis {@code SET NX}), so the "newly revoked?" contract holds without extra round-trips, and
 * entries expire via the single-use TTL — matching the SPI's "expired entries are cleaned up
 * automatically" requirement, including across nodes (the stock embedded Infinispan store is
 * node-local under {@code KC_CACHE=local}).
 */
public class RedisRevokedTokenProvider implements RevokedTokenProvider {

    private final SingleUseObjectProvider delegate;

    public RedisRevokedTokenProvider(SingleUseObjectProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public boolean put(String id, long lifespanSeconds) {
        return delegate.putIfAbsent(id + SingleUseObjectProvider.REVOKED_KEY, lifespanSeconds);
    }

    @Override
    public boolean contains(String id) {
        return delegate.contains(id + SingleUseObjectProvider.REVOKED_KEY);
    }

    @Override
    public void close() {}
}
