package balbucio.keycloak.cache.redis.cluster;

import java.util.List;

import balbucio.keycloak.cache.redis.cluster.events.AuthenticationSessionAuthNoteUpdateEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.CacheKeyInvalidatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.ClientAddedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.ClientRemovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.ClientScopeAddedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.ClientScopeRemovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.ClientUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.GroupAddedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.GroupMovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.GroupRemovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.GroupUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.InvalidationEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.RealmRemovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.RealmUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.RoleAddedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.RoleRemovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.RoleUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.UserCacheRealmInvalidationEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.UserConsentsUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.UserFederationLinkRemovedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.UserFederationLinkUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.UserFullInvalidationEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.UserUpdatedEventMixin;
import balbucio.keycloak.cache.redis.cluster.events.ClusterEventMixin;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider.DCNotify;
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
import org.keycloak.models.cache.infinispan.events.InvalidationEvent;
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

public final class ClusterEventSerializer {

    private static final Logger LOG = Logger.getLogger(ClusterEventSerializer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        // Keycloak cache invalidation events keep their payload in package-private final fields
        // (only "id" is exposed via a getter). Without field visibility the JSON would drop
        // realmId/clientId/roleName/etc., silently breaking cross-node invalidation.
        MAPPER.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        // Allowlist-only polymorphism for ClusterEvent (no open DefaultTyping).
        MAPPER.setPolymorphicTypeValidator(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("org.keycloak.models.cache.infinispan.events.")
                        .build());
        MAPPER.addMixIn(ClusterEvent.class, ClusterEventMixin.class);

        MAPPER.addMixIn(InvalidationEvent.class, InvalidationEventMixin.class);
        MAPPER.addMixIn(CacheKeyInvalidatedEvent.class, CacheKeyInvalidatedEventMixin.class);
        MAPPER.addMixIn(ClientAddedEvent.class, ClientAddedEventMixin.class);
        MAPPER.addMixIn(ClientRemovedEvent.class, ClientRemovedEventMixin.class);
        MAPPER.addMixIn(ClientScopeAddedEvent.class, ClientScopeAddedEventMixin.class);
        MAPPER.addMixIn(ClientScopeRemovedEvent.class, ClientScopeRemovedEventMixin.class);
        MAPPER.addMixIn(ClientUpdatedEvent.class, ClientUpdatedEventMixin.class);
        MAPPER.addMixIn(GroupAddedEvent.class, GroupAddedEventMixin.class);
        MAPPER.addMixIn(GroupMovedEvent.class, GroupMovedEventMixin.class);
        MAPPER.addMixIn(GroupRemovedEvent.class, GroupRemovedEventMixin.class);
        MAPPER.addMixIn(GroupUpdatedEvent.class, GroupUpdatedEventMixin.class);
        MAPPER.addMixIn(RealmRemovedEvent.class, RealmRemovedEventMixin.class);
        MAPPER.addMixIn(RealmUpdatedEvent.class, RealmUpdatedEventMixin.class);
        MAPPER.addMixIn(RoleAddedEvent.class, RoleAddedEventMixin.class);
        MAPPER.addMixIn(RoleRemovedEvent.class, RoleRemovedEventMixin.class);
        MAPPER.addMixIn(RoleUpdatedEvent.class, RoleUpdatedEventMixin.class);
        MAPPER.addMixIn(UserCacheRealmInvalidationEvent.class, UserCacheRealmInvalidationEventMixin.class);
        MAPPER.addMixIn(UserConsentsUpdatedEvent.class, UserConsentsUpdatedEventMixin.class);
        MAPPER.addMixIn(UserFederationLinkRemovedEvent.class, UserFederationLinkRemovedEventMixin.class);
        MAPPER.addMixIn(UserFederationLinkUpdatedEvent.class, UserFederationLinkUpdatedEventMixin.class);
        MAPPER.addMixIn(UserFullInvalidationEvent.class, UserFullInvalidationEventMixin.class);
        MAPPER.addMixIn(UserUpdatedEvent.class, UserUpdatedEventMixin.class);
        MAPPER.addMixIn(
                AuthenticationSessionAuthNoteUpdateEvent.class,
                AuthenticationSessionAuthNoteUpdateEventMixin.class);
    }

    private ClusterEventSerializer() {}

    public static String serialize(
            String eventKey,
            List<ClusterEvent> events,
            boolean ignoreSender,
            DCNotify dcNotify,
            String senderId)
            throws JsonProcessingException {
        ClusterMessage message = new ClusterMessage(eventKey, events, ignoreSender, dcNotify, senderId);
        String json = MAPPER.writeValueAsString(message);
        LOG.debugf("Serialized cluster message: %s", json);
        return json;
    }

    public static ClusterMessage deserialize(String json) throws JsonProcessingException {
        LOG.debugf("Deserializing cluster message: %s", json);
        return MAPPER.readValue(json, ClusterMessage.class);
    }

    public static class ClusterMessage {
        private String eventKey;
        private List<ClusterEvent> events;
        private boolean ignoreSender;
        private DCNotify dcNotify;
        private String senderId;

        public ClusterMessage() {}

        public ClusterMessage(
                String eventKey,
                List<ClusterEvent> events,
                boolean ignoreSender,
                DCNotify dcNotify,
                String senderId) {
            this.eventKey = eventKey;
            this.events = events;
            this.ignoreSender = ignoreSender;
            this.dcNotify = dcNotify;
            this.senderId = senderId;
        }

        public String getEventKey() {
            return eventKey;
        }

        public void setEventKey(String eventKey) {
            this.eventKey = eventKey;
        }

        public List<ClusterEvent> getEvents() {
            return events;
        }

        public void setEvents(List<ClusterEvent> events) {
            this.events = events;
        }

        public boolean getIgnoreSender() {
            return ignoreSender;
        }

        public void setIgnoreSender(boolean ignoreSender) {
            this.ignoreSender = ignoreSender;
        }

        public DCNotify getDcNotify() {
            return dcNotify;
        }

        public void setDcNotify(DCNotify dcNotify) {
            this.dcNotify = dcNotify;
        }

        public String getSenderId() {
            return senderId;
        }

        public void setSenderId(String senderId) {
            this.senderId = senderId;
        }
    }
}
