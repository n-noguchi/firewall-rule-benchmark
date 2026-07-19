package firewall.spark;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** RFC-4180-ish CSV directory reader. Ported from postgres-indexed-java-implementation. */
final class Csv {
    private Csv() {
    }

    static void readDirectory(Path directory, String[] expectedHeader, Consumer<String[]> consumer) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> collected = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv")).forEach(collected::add);
            collected.sort(null);
            files = collected;
        }
        if (files.isEmpty()) {
            throw new IOException("no CSV files in " + directory);
        }
        for (Path file : files) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    throw new IOException("empty CSV file: " + file);
                }
                String[] header = parseLine(headerLine);
                if (!Arrays.equals(header, expectedHeader)) {
                    throw new IOException("invalid CSV header in " + file + ": " + Arrays.toString(header));
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] row = parseLine(line);
                    if (row.length != expectedHeader.length) {
                        throw new IOException("malformed row in " + file + ": expected " + expectedHeader.length + " columns");
                    }
                    consumer.accept(row);
                }
            }
        }
    }

    static String readDatasetVersion(Path dataDirectory) throws IOException {
        Path manifest = dataDirectory.resolve("manifest.json");
        if (!Files.exists(manifest)) {
            throw new IOException("manifest.json not found in " + dataDirectory);
        }
        try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return extractJsonField(sb.toString(), "dataset_version");
        }
    }

    private static String extractJsonField(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            throw new IllegalStateException("manifest field " + field + " not found");
        }
        int colon = json.indexOf(':', idx + needle.length());
        int startQuote = json.indexOf('"', colon + 1);
        int endQuote = json.indexOf('"', startQuote + 1);
        return json.substring(startQuote + 1, endQuote);
    }

    static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        buffer.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                buffer.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                i++;
                continue;
            }
            if (c == ',') {
                fields.add(buffer.toString());
                buffer.setLength(0);
                i++;
                continue;
            }
            buffer.append(c);
            i++;
        }
        fields.add(buffer.toString());
        return fields.toArray(new String[0]);
    }

    static final class Headers {
        static final String[] POLICIES = {
                "policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"
        };
        static final String[] RULES = {
                "rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern",
                "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at"
        };
        static final String[] GROUPS = {
                "group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"
        };
        static final String[] MEMBERS = {
                "group_id", "source_ipv4", "created_at", "updated_at"
        };
        static final String[] RESULTS = {
                "access_id", "selected_policy_id", "matched_rule_id", "action"
        };
    }
}
