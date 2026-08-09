package balbucio.keycloak.cache.redis.authz.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class CachedEntityEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsGenAndPayload() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("id", "res-123");
        payload.put("name", "my-resource");
        CachedEntityEnvelope original = new CachedEntityEnvelope(7L, payload);

        String json = mapper.writeValueAsString(original);
        CachedEntityEnvelope deserialized = mapper.readValue(json, CachedEntityEnvelope.class);

        assertEquals(7L, deserialized.getGen());
        assertNotNull(deserialized.getPayload());
        assertEquals("res-123", deserialized.getPayload().get("id").asText());
        assertEquals("my-resource", deserialized.getPayload().get("name").asText());
    }

    @Test
    void payloadTreeCanBeConvertedToConcreteType() throws Exception {
        TestResource payload = new TestResource();
        payload.id = "res-456";
        payload.name = "photo-album";

        CachedEntityEnvelope envelope =
                new CachedEntityEnvelope(3L, mapper.valueToTree(payload));
        String json = mapper.writeValueAsString(envelope);

        CachedEntityEnvelope deserialized = mapper.readValue(json, CachedEntityEnvelope.class);
        TestResource back = mapper.treeToValue(deserialized.getPayload(), TestResource.class);

        assertEquals("res-456", back.id);
        assertEquals("photo-album", back.name);
    }

    @Test
    void nullPayloadRoundTripsAsNullNode() throws Exception {
        CachedEntityEnvelope original = new CachedEntityEnvelope(1L, null);
        String json = mapper.writeValueAsString(original);
        CachedEntityEnvelope deserialized = mapper.readValue(json, CachedEntityEnvelope.class);

        assertEquals(1L, deserialized.getGen());
        assertNotNull(deserialized.getPayload());
        assertTrue(deserialized.getPayload().isNull());
    }

    public static class TestResource {
        public String id;
        public String name;
    }
}
