package balbucio.keycloak.cache.redis.common;

/**
 * Converts between Redis string fields and numeric time values.
 */
public final class TimeAdapter {

    private TimeAdapter() {}

    public static Integer parseInt(String value) {
        if (value == null || value.isEmpty() || Constants.NULL_SENTINEL.equals(value)) {
            return null;
        }
        return Integer.parseInt(value);
    }

    public static Long parseLong(String value) {
        if (value == null || value.isEmpty() || Constants.NULL_SENTINEL.equals(value)) {
            return null;
        }
        return Long.parseLong(value);
    }

    public static Boolean parseBoolean(String value) {
        if (value == null || value.isEmpty() || Constants.NULL_SENTINEL.equals(value)) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    public static String stringify(Object value) {
        if (value == null) {
            return Constants.NULL_SENTINEL;
        }
        return String.valueOf(value);
    }
}
