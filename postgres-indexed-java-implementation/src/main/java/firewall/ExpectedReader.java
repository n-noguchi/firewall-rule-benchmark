package firewall;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Reads the canonical expected_results CSV directory into an access_id -> Result map. */
final class ExpectedReader {
    private ExpectedReader() {
    }

    static Map<String, EvalResult> read(Path directory) throws IOException {
        java.util.List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            java.util.List<Path> collected = new java.util.ArrayList<>();
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv")).forEach(collected::add);
            collected.sort(null);
            files = collected;
        }
        Map<String, EvalResult> results = new HashMap<>();
        for (Path path : files) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String headerLine = reader.readLine();
                if (headerLine == null || !headerLine.equals(ResultWriter.HEADER)) {
                    throw new IOException("invalid expected-result header in " + path);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] row = CsvMasterSource.parseCsvLine(line);
                    if (row.length != 4) {
                        throw new IOException("malformed expected row in " + path);
                    }
                    if (row[0].isEmpty() || row[1].isEmpty() || !Loader.actionValid(row[3])) {
                        throw new IOException("malformed expected result in " + path);
                    }
                    if (results.containsKey(row[0])) {
                        throw new IOException("duplicate expected access_id " + row[0]);
                    }
                    results.put(row[0], new EvalResult(row[0], row[1], row[2].isEmpty() ? null : row[2], row[3]));
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unused")
    private static Path toPath(String first, String... more) {
        return Paths.get(first, more);
    }
}
