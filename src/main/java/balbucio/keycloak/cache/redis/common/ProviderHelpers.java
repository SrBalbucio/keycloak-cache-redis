package balbucio.keycloak.cache.redis.common;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small config/parsing helpers shared across factories.
 */
public final class ProviderHelpers {

    private static final Pattern DURATION =
            Pattern.compile("^\\s*(\\d+)\\s*(ms|s|m)?\\s*$", Pattern.CASE_INSENSITIVE);

    private ProviderHelpers() {}

    /**
     * Parses timeouts such as {@code 2000}, {@code 2s}, {@code 500ms} into a {@link Duration}.
     */
    public static Duration parseTimeout(String raw, Duration defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        Matcher m = DURATION.matcher(raw.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid timeout value: " + raw);
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "ms" : m.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofMillis(amount);
        };
    }

    public static List<HostPort> parseNodes(String nodes) {
        List<HostPort> result = new ArrayList<>();
        if (nodes == null || nodes.isBlank()) {
            return result;
        }
        for (String part : nodes.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.lastIndexOf(':');
            if (idx <= 0 || idx == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid redis node (expected host:port): " + trimmed);
            }
            String host = trimmed.substring(0, idx).trim();
            int port = Integer.parseInt(trimmed.substring(idx + 1).trim());
            result.add(new HostPort(host, port));
        }
        return result;
    }

    public record HostPort(String host, int port) {}
}
