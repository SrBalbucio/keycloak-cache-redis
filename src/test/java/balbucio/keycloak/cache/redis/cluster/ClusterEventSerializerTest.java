package balbucio.keycloak.cache.redis.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import balbucio.keycloak.cache.redis.cluster.ClusterEventSerializer.ClusterMessage;
import org.junit.jupiter.api.Test;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider.DCNotify;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.cache.infinispan.events.AuthenticationSessionAuthNoteUpdateEvent;
import org.keycloak.models.cache.infinispan.events.CacheKeyInvalidatedEvent;
import org.keycloak.models.cache.infinispan.events.ClientAddedEvent;
import org.keycloak.models.cache.infinispan.events.ClientRemovedEvent;
import org.keycloak.models.cache.infinispan.events.ClientScopeAddedEvent;
import org.keycloak.models.cache.infinispan.events.ClientScopeRemovedEvent;
import org.keycloak.models.cache.infinispan.events.ClientUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.GroupAddedEvent;
import org.keycloak.models.cache.infinispan.events.GroupMovedEvent;
import org.keycloak.models.cache.infinispan.events.GroupRemovedEvent;
import org.keycloak.models.cache.infinispan.events.GroupUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.RealmRemovedEvent;
import org.keycloak.models.cache.infinispan.events.RealmUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.RoleAddedEvent;
import org.keycloak.models.cache.infinispan.events.RoleRemovedEvent;
import org.keycloak.models.cache.infinispan.events.RoleUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserCacheRealmInvalidationEvent;
import org.keycloak.models.cache.infinispan.events.UserConsentsUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserFederationLinkRemovedEvent;
import org.keycloak.models.cache.infinispan.events.UserFederationLinkUpdatedEvent;
import org.keycloak.models.cache.infinispan.events.UserFullInvalidationEvent;
import org.keycloak.models.cache.infinispan.events.UserUpdatedEvent;

class ClusterEventSerializerTest {

    @Test
    void roundTripAuthenticationSessionAuthNoteUpdate() throws Exception {
        Map<String, String> notes = new HashMap<>();
        notes.put("k1", "v1");
        notes.put("k2", "v2");

        AuthenticationSessionAuthNoteUpdateEvent event =
                AuthenticationSessionAuthNoteUpdateEvent.create("auth-1", "tab-1", notes);

        String json = ClusterEventSerializer.serialize("task", List.of(event), true, DCNotify.ALL_DCS, "node-1");
        ClusterMessage msg = ClusterEventSerializer.deserialize(json);

        AuthenticationSessionAuthNoteUpdateEvent back =
                (AuthenticationSessionAuthNoteUpdateEvent) msg.getEvents().get(0);
        assertEquals(event, back);
        assertEquals(notes, back.getAuthNotesFragment());
    }

    @Test
    void roundTripCacheKeyInvalidated() throws Exception {
        roundTrip(new CacheKeyInvalidatedEvent("cache-key-1"));
    }

    @Test
    void roundTripClientAdded() throws Exception {
        roundTrip(ClientAddedEvent.create("client-uuid-1", "realm-1"));
    }

    @Test
    void roundTripClientRemoved() throws Exception {
        roundTrip(clientRemovedEvent());
    }

    @Test
    void roundTripClientScopeAdded() throws Exception {
        roundTrip(ClientScopeAddedEvent.create("scope-1", "realm-1"));
    }

    @Test
    void roundTripClientScopeRemoved() throws Exception {
        roundTrip(ClientScopeRemovedEvent.create("scope-2", "realm-1"));
    }

    @Test
    void roundTripClientUpdated() throws Exception {
        roundTrip(ClientUpdatedEvent.create("client-uuid-2", "client-id-2", "realm-1"));
    }

    @Test
    void roundTripGroupAdded() throws Exception {
        roundTrip(GroupAddedEvent.create("group-1", "parent-1", "realm-1"));
    }

    @Test
    void roundTripGroupAddedWithNullParent() throws Exception {
        roundTrip(GroupAddedEvent.create("group-1b", null, "realm-1"));
    }

    @Test
    void roundTripGroupMoved() throws Exception {
        roundTrip(groupMovedEvent());
    }

    @Test
    void roundTripGroupRemoved() throws Exception {
        roundTrip(new GroupRemovedEvent("group-3", "realm-1", "parent-3"));
    }

    @Test
    void roundTripGroupUpdated() throws Exception {
        roundTrip(GroupUpdatedEvent.create("group-4"));
    }

    @Test
    void roundTripRealmRemoved() throws Exception {
        roundTrip(RealmRemovedEvent.create("realm-1", "realm-name-1"));
    }

    @Test
    void roundTripRealmUpdated() throws Exception {
        roundTrip(RealmUpdatedEvent.create("realm-2", "realm-name-2"));
    }

    @Test
    void roundTripRoleAdded() throws Exception {
        roundTrip(RoleAddedEvent.create("role-1", "container-1", "role-name-1"));
    }

    @Test
    void roundTripRoleRemoved() throws Exception {
        roundTrip(RoleRemovedEvent.create("role-2", "role-name-2", "container-2"));
    }

    @Test
    void roundTripRoleUpdated() throws Exception {
        roundTrip(RoleUpdatedEvent.create("role-3", "role-name-3", "container-3"));
    }

    @Test
    void roundTripUserCacheRealmInvalidation() throws Exception {
        roundTrip(UserCacheRealmInvalidationEvent.create("realm-3"));
    }

    @Test
    void roundTripUserConsentsUpdated() throws Exception {
        roundTrip(UserConsentsUpdatedEvent.create("user-1"));
    }

    @Test
    void roundTripUserFederationLinkUpdated() throws Exception {
        roundTrip(UserFederationLinkUpdatedEvent.create("user-2"));
    }

    @Test
    void roundTripUserFederationLinkRemoved() throws Exception {
        roundTrip(
                UserFederationLinkRemovedEvent.create(
                        "user-3", "realm-4", new FederatedIdentityModel("provider-1", "social-1", "user-3")));
    }

    @Test
    void roundTripUserFullInvalidationWithFederation() throws Exception {
        roundTrip(
                UserFullInvalidationEvent.create(
                        "user-4",
                        "alice",
                        "alice@example.com",
                        "realm-5",
                        true,
                        Stream.of(new FederatedIdentityModel("github", "12345", "alice"))));
    }

    @Test
    void roundTripUserFullInvalidationWithoutFederation() throws Exception {
        roundTrip(UserFullInvalidationEvent.create("user-4b", "bob", null, "realm-5", false, Stream.empty()));
    }

    @Test
    void roundTripUserUpdated() throws Exception {
        roundTrip(UserUpdatedEvent.create("user-5", "bob", "bob@example.com", "realm-6"));
    }

    @Test
    void roundTripPreservesMessageMetadata() throws Exception {
        ClusterEvent event = GroupUpdatedEvent.create("group-meta");
        String json = ClusterEventSerializer.serialize("my-task", List.of(event), false, DCNotify.ALL_DCS, "node-42");

        ClusterMessage msg = ClusterEventSerializer.deserialize(json);

        assertEquals("my-task", msg.getEventKey());
        assertFalse(msg.getIgnoreSender());
        assertEquals(DCNotify.ALL_DCS, msg.getDcNotify());
        assertEquals("node-42", msg.getSenderId());
    }

    @Test
    void roundTripMultipleEventsInOneMessage() throws Exception {
        List<ClusterEvent> events =
                List.of(
                        ClientAddedEvent.create("c1", "r1"),
                        GroupUpdatedEvent.create("g1"),
                        RoleUpdatedEvent.create("r-1", "role-a", "container-a"));

        String json = ClusterEventSerializer.serialize("bulk", events, true, DCNotify.ALL_DCS, "node-1");
        ClusterMessage msg = ClusterEventSerializer.deserialize(json);

        assertEquals(events, msg.getEvents());
    }

    @Test
    void roundTripAllDcNotifyModes() throws Exception {
        for (DCNotify dc : DCNotify.values()) {
            ClusterEvent event = GroupUpdatedEvent.create("dc-" + dc.name());
            String json = ClusterEventSerializer.serialize("dc-test", List.of(event), true, dc, "node-1");
            ClusterMessage msg = ClusterEventSerializer.deserialize(json);
            assertEquals(dc, msg.getDcNotify());
        }
    }

    @Test
    void serializedJsonCarriesPolymorphicTypeInfo() throws Exception {
        ClusterEvent event = ClientAddedEvent.create("client-uuid-x", "realm-x");
        String json = ClusterEventSerializer.serialize("k", List.of(event), true, DCNotify.ALL_DCS, "n1");

        assertTrue(json.contains("@class"), "expected polymorphic @class marker in: " + json);
        assertTrue(json.contains(ClientAddedEvent.class.getName()), "expected concrete class in: " + json);
    }

    @Test
    void rejectsNonAllowlistedEventTypes() {
        String json =
                """
                {"eventKey":"k","events":[{"@class":"java.util.HashMap","a":1}],"ignoreSender":true,"dcNotify":"ALL_DCS","senderId":"n1"}
                """;
        assertThrows(InvalidTypeIdException.class, () -> ClusterEventSerializer.deserialize(json));
    }

    @Test
    void roleAddedKeepsRoleNameInJson() throws Exception {
        ClusterEvent event = RoleAddedEvent.create("role-1", "container-1", "role-name-1");
        String json = ClusterEventSerializer.serialize("k", List.of(event), true, DCNotify.ALL_DCS, "n1");

        assertTrue(json.contains("role-name-1"), "expected roleName to survive serialization: " + json);
    }

    private static void roundTrip(ClusterEvent event) throws Exception {
        String json = ClusterEventSerializer.serialize("task-key", List.of(event), true, DCNotify.ALL_DCS, "node-1");

        ClusterMessage msg = ClusterEventSerializer.deserialize(json);

        assertEquals("task-key", msg.getEventKey());
        assertTrue(msg.getIgnoreSender());
        assertEquals(DCNotify.ALL_DCS, msg.getDcNotify());
        assertEquals("node-1", msg.getSenderId());
        assertEquals(1, msg.getEvents().size());
        assertEquals(event, msg.getEvents().get(0));
        assertEquals(event.toString(), msg.getEvents().get(0).toString());
    }

    private static ClientRemovedEvent clientRemovedEvent() throws Exception {
        Constructor<ClientRemovedEvent> ctor =
                ClientRemovedEvent.class.getDeclaredConstructor(
                        String.class, String.class, String.class, Map.class);
        ctor.setAccessible(true);
        Map<String, String> roles = new HashMap<>();
        roles.put("role-1", "role-name-1");
        return ctor.newInstance("client-uuid-3", "realm-1", "client-id-3", roles);
    }

    private static GroupMovedEvent groupMovedEvent() throws Exception {
        Constructor<GroupMovedEvent> ctor =
                GroupMovedEvent.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("group-5", "new-parent-5", null, "realm-1");
    }
}
