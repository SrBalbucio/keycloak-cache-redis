package balbucio.keycloak.cache.redis.cluster.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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

/**
 * Explicit polymorphic allowlist for {@link org.keycloak.cluster.ClusterEvent} payloads on the Redis
 * PUBSUB channel. Replaces open {@code DefaultTyping} so unknown types cannot be deserialized.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
    @JsonSubTypes.Type(CacheKeyInvalidatedEvent.class),
    @JsonSubTypes.Type(ClientAddedEvent.class),
    @JsonSubTypes.Type(ClientRemovedEvent.class),
    @JsonSubTypes.Type(ClientScopeAddedEvent.class),
    @JsonSubTypes.Type(ClientScopeRemovedEvent.class),
    @JsonSubTypes.Type(ClientUpdatedEvent.class),
    @JsonSubTypes.Type(GroupAddedEvent.class),
    @JsonSubTypes.Type(GroupMovedEvent.class),
    @JsonSubTypes.Type(GroupRemovedEvent.class),
    @JsonSubTypes.Type(GroupUpdatedEvent.class),
    @JsonSubTypes.Type(RealmRemovedEvent.class),
    @JsonSubTypes.Type(RealmUpdatedEvent.class),
    @JsonSubTypes.Type(RoleAddedEvent.class),
    @JsonSubTypes.Type(RoleRemovedEvent.class),
    @JsonSubTypes.Type(RoleUpdatedEvent.class),
    @JsonSubTypes.Type(UserCacheRealmInvalidationEvent.class),
    @JsonSubTypes.Type(UserConsentsUpdatedEvent.class),
    @JsonSubTypes.Type(UserFederationLinkRemovedEvent.class),
    @JsonSubTypes.Type(UserFederationLinkUpdatedEvent.class),
    @JsonSubTypes.Type(UserFullInvalidationEvent.class),
    @JsonSubTypes.Type(UserUpdatedEvent.class),
    @JsonSubTypes.Type(AuthenticationSessionAuthNoteUpdateEvent.class)
})
public abstract class ClusterEventMixin {}
