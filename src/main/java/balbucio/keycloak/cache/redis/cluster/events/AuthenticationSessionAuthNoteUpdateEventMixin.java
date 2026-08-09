package balbucio.keycloak.cache.redis.cluster.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.Map;

public abstract class AuthenticationSessionAuthNoteUpdateEventMixin {

  @JsonCreator
  public AuthenticationSessionAuthNoteUpdateEventMixin(
      @JsonProperty("authNotesFragment") Map<String, String> authNotesFragment,
      @JsonProperty("authSessionId") @JsonSetter(nulls = Nulls.AS_EMPTY) String authSessionId,
      @JsonProperty("tabId") @JsonSetter(nulls = Nulls.AS_EMPTY) String tabId) {}
}
