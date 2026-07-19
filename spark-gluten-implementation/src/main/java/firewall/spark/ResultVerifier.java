package firewall.spark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Compares a produced results.csv against expected_results and reports the diff (if any). */
final class ResultVerifier {
    private ResultVerifier() {
    }

    static void verify(Path expectedDirectory, Path resultsCsv) throws IOException {
        Map<String, String[]> expected = readResults(expectedDirectory);
        Map<String, String[]> actual = readResultsSingle(resultsCsv);

        if (actual.size() != expected.size()) {
            throw new IOException("result count mismatch: evaluated=" + actual.size()
                    + " expected=" + expected.size());
        }
        int firstMismatch = 0;
        for (Map.Entry<String, String[]> entry : expected.entrySet()) {
            String[] want = entry.getValue();
            String[] got = actual.get(entry.getKey());
            if (got == null) {
                throw new IOException("missing result for " + entry.getKey());
            }
            if (!same(want, got)) {
                firstMismatch++;
                if (firstMismatch <= 5) {
                    System.err.println("mismatch " + entry.getKey()
                            + ": got=" + rowToText(got) + " want=" + rowToText(want));
                }
            }
        }
        if (firstMismatch > 0) {
            throw new IOException(firstMismatch + " results did not match expected");
        }
        System.out.println("validated " + expected.size() + " results against " + resultsCsv);
    }

    private static boolean same(String[] a, String[] b) {
        return normalize(a[1]).equals(normalize(b[1]))
                && normalize(a[2]).equals(normalize(b[2]))
                && normalize(a[3]).equals(normalize(b[3]));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value;
    }

    private static String rowToText(String[] row) {
        return "[" + row[0] + ", " + row[1] + ", " + row[2] + ", " + row[3] + "]";
    }

    private static Map<String, String[]> readResults(Path directory) throws IOException {
        Map<String, String[]> rows = new HashMap<>();
        try (Stream<Path> stream = Files.list(directory)) {
            var files = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted()
                    .toList();
            if (files.isEmpty()) {
                throw new IOException("no expected_results CSV in " + directory);
            }
            for (Path file : files) {
                readCsvRows(file, rows);
            }
        }
        return rows;
    }

    private static Map<String, String[]> readResultsSingle(Path file) throws IOException {
        Map<String, String[]> rows = new HashMap<>();
        readCsvRows(file, rows);
        return rows;
    }

    private static void readCsvRows(Path file, Map<String, String[]> sink) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("empty CSV: " + file);
            }
            String[] header = Csv.parseLine(headerLine);
            if (!headerMatches(header)) {
                throw new IOException("unexpected CSV header in " + file + ": "
                        + java.util.Arrays.toString(header));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] row = Csv.parseLine(line);
                if (row.length < 4) {
                    throw new IOException("malformed row in " + file + ": " + line);
                }
                String[] copy = new String[]{row[0], row[1], row[2], row[3]};
                if (sink.put(row[0], copy) != null) {
                    throw new IOException("duplicate access_id in " + file + ": " + row[0]);
                }
            }
        }
    }

    private static boolean headerMatches(String[] header) {
        if (header.length < 4) {
            return false;
        }
        return "access_id".equals(header[0])
                && "selected_policy_id".equals(header[1])
                && "matched_rule_id".equals(header[2])
                && "action".equals(header[3]);
    }
}
