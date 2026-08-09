package balbucio.keycloak.cache.redis.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TimeAdapterTest {

    @Test
    void parseInteger() {
        assertEquals(Integer.valueOf(42), TimeAdapter.parseInt("42"));
        assertEquals(Integer.valueOf(-7), TimeAdapter.parseInt("-7"));
    }

    @Test
    void parseIntegerNullish() {
        assertNull(TimeAdapter.parseInt(null));
        assertNull(TimeAdapter.parseInt(""));
        assertNull(TimeAdapter.parseInt(Constants.NULL_SENTINEL));
    }

    @Test
    void parseLong() {
        assertEquals(Long.valueOf(42L), TimeAdapter.parseLong("42"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), TimeAdapter.parseLong(Long.toString(Long.MAX_VALUE)));
    }

    @Test
    void parseLongNullish() {
        assertNull(TimeAdapter.parseLong(null));
        assertNull(TimeAdapter.parseLong(""));
        assertNull(TimeAdapter.parseLong(Constants.NULL_SENTINEL));
    }

    @Test
    void parseBoolean() {
        assertEquals(Boolean.TRUE, TimeAdapter.parseBoolean("true"));
        assertEquals(Boolean.TRUE, TimeAdapter.parseBoolean("TRUE"));
        assertEquals(Boolean.FALSE, TimeAdapter.parseBoolean("false"));
    }

    @Test
    void parseBooleanNullish() {
        assertNull(TimeAdapter.parseBoolean(null));
        assertNull(TimeAdapter.parseBoolean(""));
        assertNull(TimeAdapter.parseBoolean(Constants.NULL_SENTINEL));
    }

    @Test
    void parseBooleanGarbageIsFalse() {
        assertFalse(TimeAdapter.parseBoolean("not-a-bool"));
        assertFalse(TimeAdapter.parseBoolean("yes"));
    }

    @Test
    void stringifyNullUsesSentinel() {
        assertEquals(Constants.NULL_SENTINEL, TimeAdapter.stringify(null));
    }

    @Test
    void stringifyPrimitivesAndStrings() {
        assertEquals("123", TimeAdapter.stringify(123));
        assertEquals("12.5", TimeAdapter.stringify(12.5));
        assertEquals("true", TimeAdapter.stringify(true));
        assertEquals("abc", TimeAdapter.stringify("abc"));
    }

    @Test
    void sentinelRoundTripsThroughStringify() {
        assertNull(TimeAdapter.parseLong(TimeAdapter.stringify(null)));
    }
}
