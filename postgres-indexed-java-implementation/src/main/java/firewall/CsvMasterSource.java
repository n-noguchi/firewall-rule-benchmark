package firewall;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** MasterSource backed by the canonical CSV directory layout. */
final class CsvMasterSource implements MasterSource {
    private final Path directory;

    CsvMasterSource(Path directory) {
        this.directory = directory;
    }

    @Override
    public String dataVersion() throws IOException {
        // Mirrors Loader#readDatasetVersion but kept here for parity with the Go masterSource interface.
        return Loader.readDatasetVersion(directory);
    }

    @Override
    public void readPolicies(RowConsumer consumer) throws IOException, SQLException, InterruptedException {
        readCsvDirectory(directory.resolve("policies"), PoliciesHeader.EXPECTED, consumer);
    }

    @Override
    public void readRules(RowConsumer consumer) throws IOException, SQLException, InterruptedException {
        readCsvDirectory(directory.resolve("firewall_rules"), RulesHeader.EXPECTED, consumer);
    }

    @Override
    public void readGroups(RowConsumer consumer) throws IOException, SQLException, InterruptedException {
        readCsvDirectory(directory.resolve("source_ip_groups"), GroupsHeader.EXPECTED, consumer);
    }

    @Override
    public void readMembers(RowConsumer consumer) throws IOException, SQLException, InterruptedException {
        readCsvDirectory(directory.resolve("source_ip_group_members"), MembersHeader.EXPECTED, consumer);
    }

    static void readCsvDirectory(Path directory, String[] expectedHeader, RowConsumer consumer) throws IOException, InterruptedException, SQLException {
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
                String[] header = parseCsvLine(headerLine);
                if (!Arrays.equals(header, expectedHeader)) {
                    throw new IOException("invalid CSV header in " + file + ": " + Arrays.toString(header));
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] row = parseCsvLine(line);
                    if (row.length != expectedHeader.length) {
                        throw new IOException("malformed row in " + file + ": expected " + expectedHeader.length + " columns");
                    }
                    consumer.accept(row);
                }
            }
        }
    }

    /**
     * RFC-4180-ish CSV parser: double-quoted fields with "" escapes. Sufficient
     * for the benchmark dataset (no embedded newlines, fields never contain
     * unescaped delimiters).
     */
    static String[] parseCsvLine(String line) {
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

    static final class PoliciesHeader {
        static final String[] EXPECTED = {
                "policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"
        };
    }

    static final class RulesHeader {
        static final String[] EXPECTED = {
                "rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern",
                "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at"
        };
    }

    static final class GroupsHeader {
        static final String[] EXPECTED = {
                "group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"
        };
    }

    static final class MembersHeader {
        static final String[] EXPECTED = {
                "group_id", "source_ipv4", "created_at", "updated_at"
        };
    }

    @SuppressWarnings("unused")
    private static Path path(String first, String... more) {
        return Paths.get(first, more);
    }
}
