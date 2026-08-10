package balbucio.keycloak.cache.redis.userSession;

import static org.junit.jupiter.api.Assertions.assertTrue;

import balbucio.keycloak.cache.redis.authSession.AuthSessionIndexes;
import balbucio.keycloak.cache.redis.authSession.RootAuthenticationSessionKey;
import org.junit.jupiter.api.Test;

class UserSessionKeyLayoutTest {

    @Test
    void userAndAuthSessionKeysUseRealmHashTags() {
        String realm = "realm-abc";
        assertTrue(UserSessionKey.of(realm, "s1", false).key().contains("{" + realm + "}"));
        assertTrue(UserSessionKey.of(realm, "s1", true).key().contains("{" + realm + "}"));
        assertTrue(
                AuthenticatedClientSessionKey.of(realm, "s1", "c1", false)
                        .key()
                        .contains("{" + realm + "}"));
        assertTrue(UserSessionIndexes.realmIndex(realm, false).contains("{" + realm + "}"));
        assertTrue(UserSessionIndexes.clientIndex(realm, "c1", false).contains("{" + realm + "}"));
        assertTrue(UserSessionIndexes.clientZIndex(realm, "c1", false).contains("{" + realm + "}"));
        assertTrue(UserSessionIndexes.clientStats(realm, "c1", false).contains("{" + realm + "}"));
        assertTrue(RootAuthenticationSessionKey.of(realm, "r1").key().contains("{" + realm + "}"));
        assertTrue(AuthSessionIndexes.realmIndex(realm).contains("{" + realm + "}"));
    }
}
