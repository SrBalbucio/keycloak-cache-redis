package balbucio.keycloak.cache.redis.authz.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Envelope wrapping a cached authorization entity with the generation (invalidation epoch) under
 * which it was stored. The payload is kept as a {@link JsonNode} tree so that the target concrete
 * type is supplied by the caller at read time via {@code ObjectMapper.treeToValue}.
 */
public class CachedEntityEnvelope {

    @JsonProperty("gen")
    private long gen;

    @JsonProperty("payload")
    private JsonNode payload;

    public CachedEntityEnvelope() {}

    public CachedEntityEnvelope(long gen, JsonNode payload) {
        this.gen = gen;
        this.payload = payload;
    }

    public long getGen() {
        return gen;
    }

    public void setGen(long gen) {
        this.gen = gen;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }
}
