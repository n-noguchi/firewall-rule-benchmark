package firewall.spark;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Minimal flag parser that supports --key=value, --key value and boolean flags. */
final class Flags {
    private final Map<String, String> values = new HashMap<>();

    private Flags() {
    }

    static Flags parse(String[] args, String... knownKeys) {
        Set<String> known = new HashSet<>(Arrays.asList(knownKeys));
        Flags flags = new Flags();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key;
            String value;
            if (arg.contains("=")) {
                int eq = arg.indexOf('=');
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                } else {
                    value = "true";
                }
            }
            if (!known.isEmpty() && !known.contains(key)) {
                throw new IllegalArgumentException("unknown flag --" + key);
            }
            flags.values.put(key, value);
        }
        return flags;
    }

    String value(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    int intValue(String key, int fallback) {
        String value = values.get(key);
        if (value == null || value.isEmpty() || "true".equals(value)) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    long longValue(String key, long fallback) {
        String value = values.get(key);
        if (value == null || value.isEmpty() || "true".equals(value)) {
            return fallback;
        }
        return Long.parseLong(value);
    }

    double doubleValue(String key, double fallback) {
        String value = values.get(key);
        if (value == null || value.isEmpty() || "true".equals(value)) {
            return fallback;
        }
        return Double.parseDouble(value);
    }
}
