package balbucio.keycloak.cache.redis.authz.cache;

import balbucio.keycloak.cache.redis.common.RedisKeySpace;

/**
 * Builds Redis keys for the authorization cache-aside layer.
 *
 * <p>All keys are prefixed with the global {@link RedisKeySpace} prefix (e.g. {@code kc:}).
 * Entity keys hold serialized {@code Cached*} snapshots; generation keys hold a monotonically
 * increasing integer used for bulk invalidation per resource server.
 */
public final class AuthorizationCacheKey {

    private static final String NAMESPACE = "authz";

    private static final String RESOURCE = NAMESPACE + ":resource:";
    private static final String RESOURCE_BY_NAME = ":name:";
    private static final String SCOPE = NAMESPACE + ":scope:";
    private static final String SCOPE_BY_NAME = ":name:";
    private static final String POLICY = NAMESPACE + ":policy:";
    private static final String POLICY_BY_RESOURCE = ":resource:";
    private static final String RESOURCE_SERVER = NAMESPACE + ":resource-server:";
    private static final String RESOURCE_SERVER_BY_CLIENT = "client:";
    private static final String PERMISSION_TICKET = NAMESPACE + ":permission-ticket:";
    private static final String RS_GENERATION = NAMESPACE + ":rs-gen:";

    private AuthorizationCacheKey() {}

    public static String resourceById(String id) {
        return RedisKeySpace.key(RESOURCE + id);
    }

    public static String resourceByName(String resourceServerId, String name) {
        return RedisKeySpace.key(RESOURCE + resourceServerId + RESOURCE_BY_NAME + name);
    }

    public static String scopeById(String id) {
        return RedisKeySpace.key(SCOPE + id);
    }

    public static String scopeByName(String resourceServerId, String name) {
        return RedisKeySpace.key(SCOPE + resourceServerId + SCOPE_BY_NAME + name);
    }

    public static String policyById(String id) {
        return RedisKeySpace.key(POLICY + id);
    }

    public static String policyByResource(String resourceServerId, String resourceId) {
        return RedisKeySpace.key(POLICY + resourceServerId + POLICY_BY_RESOURCE + resourceId);
    }

    public static String resourceServerById(String id) {
        return RedisKeySpace.key(RESOURCE_SERVER + id);
    }

    public static String resourceServerByClient(String clientId) {
        return RedisKeySpace.key(RESOURCE_SERVER + RESOURCE_SERVER_BY_CLIENT + clientId);
    }

    public static String permissionTicketById(String id) {
        return RedisKeySpace.key(PERMISSION_TICKET + id);
    }

    public static String generation(String resourceServerId) {
        return RedisKeySpace.key(RS_GENERATION + resourceServerId);
    }
}
