package firewall;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Replicates the benchmark runner used by the Go implementations: spawns
 * /benchmark/run-batch for the batch division and walks a Keep-Alive HTTP/1.1
 * connection for the sequential division.
 */
final class BenchmarkRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BenchmarkRunner() {
    }

    record Report(@JsonProperty("mode") String mode,
                  @JsonProperty("started_at") String startedAt,
                  @JsonProperty("completed_at") String completedAt,
                  @JsonProperty("warmup_runs") int warmupRuns,
                  @JsonProperty("measured_runs") int measuredRuns,
                  @JsonProperty("access_count") int accessCount,
                  @JsonProperty("validation_performed") boolean validationPerformed,
                  @JsonProperty("all_correct") boolean allCorrect,
                  @JsonProperty("batch") List<BatchRun> batch,
                  @JsonProperty("sequential") List<SequentialRun> sequential) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BatchRun(@JsonProperty("run") int run,
                    @JsonProperty("elapsed_ns") long elapsedNs,
                    @JsonProperty("validated") boolean validated,
                    @JsonProperty("correct") boolean correct) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SequentialRun(@JsonProperty("run") int run,
                         @JsonProperty("total_ns") long totalNs,
                         @JsonProperty("p50_ns") long p50Ns,
                         @JsonProperty("p99_ns") long p99Ns,
                         @JsonProperty("maximum_ns") long maximumNs,
                         @JsonProperty("correct") boolean correct) {}

    static int run(String mode, String input, String expectedDir, String reportPath,
                   int warmup, int runs, long cooldownMs, String serverUrl,
                   String batchExecutable, String outputDirectory, int workers) throws Exception {
        if (warmup < 0 || runs < 1 || cooldownMs < 0) {
            throw new IllegalArgumentException("--runs >= 1, --warmup >= 0 and nonnegative --cooldown are required");
        }
        if (!"batch".equals(mode) && !"sequential".equals(mode)) {
            throw new IllegalArgumentException("--mode batch|sequential is required");
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("--input is required");
        }
        List<AccessLog> accesses = AccessLogReader.read(Paths.get(input));
        Map<String, EvalResult> expected = null;
        if (expectedDir != null && !expectedDir.isEmpty()) {
            expected = ExpectedReader.read(Paths.get(expectedDir));
            verifyAccessSet(accesses, expected);
        }
        String startedAt = java.time.Instant.now().toString();
        Report report;
        List<BatchRun> batchRuns = null;
        List<SequentialRun> seqRuns = null;
        if ("batch".equals(mode)) {
            if (outputDirectory == null || outputDirectory.isEmpty()) {
                throw new IllegalArgumentException("--output-directory is required in batch mode");
            }
            batchRuns = measureBatch(batchExecutable, input, outputDirectory, expected, workers, warmup, runs, cooldownMs);
        } else {
            seqRuns = measureSequential(serverUrl, accesses, expected, warmup, runs, cooldownMs);
        }
        boolean validationPerformed = expected != null;
        boolean allCorrect = validationPerformed;
        report = new Report(mode, startedAt, java.time.Instant.now().toString(), warmup, runs, accesses.size(),
                validationPerformed, allCorrect, batchRuns, seqRuns);
        byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        if (reportPath != null && !reportPath.isEmpty()) {
            Path parent = Paths.get(reportPath).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(Paths.get(reportPath), json);
        }
        System.out.write(json);
        System.out.println();
        return 0;
    }

    private static void verifyAccessSet(List<AccessLog> accesses, Map<String, EvalResult> expected) throws IOException {
        if (accesses.size() != expected.size()) {
            throw new IOException("input/expected count mismatch: input=" + accesses.size() + " expected=" + expected.size());
        }
        for (AccessLog access : accesses) {
            if (!expected.containsKey(access.accessId)) {
                throw new IOException("missing expected result for " + access.accessId);
            }
        }
    }

    private static List<BatchRun> measureBatch(String executable, String input, String outputDirectory,
                                                Map<String, EvalResult> expected, int workers, int warmup, int runs, long cooldownMs) throws Exception {
        Files.createDirectories(Paths.get(outputDirectory));
        int totalRuns = warmup + runs;
        List<BatchRun> measurements = new ArrayList<>(runs);
        // Talk to /internal/run-batch directly so we do not depend on the shell wrapper
        // that delegates to the batch-client subcommand. This keeps timing tight and
        // avoids filesystem visibility issues across process boundaries.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI endpoint = URI.create(trimEnd(executable) + "/internal/run-batch");
        for (int iteration = 0; iteration < totalRuns; iteration++) {
            Path output = Paths.get(outputDirectory, String.format("batch-run-%03d.csv", iteration + 1));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("input", input);
            payload.put("output", output.toString());
            if (workers != 0) payload.put("workers", workers);
            byte[] body = Json.MAPPER.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            long start = System.nanoTime();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.nanoTime() - start;
            if (response.statusCode() != 200) {
                throw new IOException("batch iteration " + (iteration + 1) + " failed: status=" + response.statusCode());
            }
            Map<String, EvalResult> actual = readResultFileWithRetry(output, 10);
            boolean validated = expected != null;
            if (validated) {
                verifyResultSet(actual, expected);
            }
            if (iteration >= warmup) {
                measurements.add(new BatchRun(iteration - warmup + 1, elapsed, validated, validated));
            }
            if (iteration + 1 < totalRuns && cooldownMs > 0) {
                TimeUnit.MILLISECONDS.sleep(cooldownMs);
            }
        }
        return measurements;
    }

    private static List<SequentialRun> measureSequential(String serverUrl, List<AccessLog> accesses,
                                                          Map<String, EvalResult> expected, int warmup, int runs, long cooldownMs) throws Exception {
        List<byte[]> payloads = new ArrayList<>(accesses.size());
        for (AccessLog access : accesses) {
            payloads.add(MAPPER.writeValueAsBytes(access));
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI endpoint = URI.create(trimEnd(serverUrl) + "/v1/firewall/evaluate");
        int totalRuns = warmup + runs;
        List<SequentialRun> measurements = new ArrayList<>(runs);
        for (int iteration = 0; iteration < totalRuns; iteration++) {
            long[] latencies = new long[accesses.size()];
            long total = 0;
            for (int index = 0; index < accesses.size(); index++) {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payloads.get(index)))
                        .build();
                long start = System.nanoTime();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                long elapsed = System.nanoTime() - start;
                if (response.statusCode() != 200) {
                    throw new IOException("sequential iteration " + (iteration + 1) + " access " + accesses.get(index).accessId + ": status=" + response.statusCode());
                }
                verifySequentialResponse(response.body(), accesses.get(index).accessId, expected == null ? null : expected.get(accesses.get(index).accessId));
                latencies[index] = elapsed;
                total += elapsed;
            }
            if (iteration >= warmup) {
                long[] sorted = Arrays.copyOf(latencies, latencies.length);
                Arrays.sort(sorted);
                measurements.add(new SequentialRun(iteration - warmup + 1, total,
                        nearestRank(sorted, 0.50), nearestRank(sorted, 0.99), sorted[sorted.length - 1], true));
            }
            if (iteration + 1 < totalRuns && cooldownMs > 0) {
                TimeUnit.MILLISECONDS.sleep(cooldownMs);
            }
        }
        return measurements;
    }

    private static long nearestRank(long[] sorted, double percentile) {
        int position = (int) (sorted.length * percentile + 0.999999999) - 1;
        if (position < 0) position = 0;
        if (position >= sorted.length) position = sorted.length - 1;
        return sorted[position];
    }

    private static void verifySequentialResponse(byte[] body, String accessId, EvalResult expected) throws IOException {
        if (expected == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> response = MAPPER.readValue(body, Map.class);
        Object matched = response.get("matched_rule_id");
        String matchedId = matched == null ? "" : matched.toString();
        String selectedPolicy = String.valueOf(response.get("selected_policy_id"));
        String action = String.valueOf(response.get("action"));
        String responseAccessId = String.valueOf(response.get("access_id"));
        String expectedMatched = expected.matchedRuleId == null ? "" : expected.matchedRuleId;
        if (!responseAccessId.equals(expected.accessId) || !selectedPolicy.equals(expected.selectedPolicyId)
                || !matchedId.equals(expectedMatched) || !action.equals(expected.action)) {
            throw new IOException("access " + accessId + " mismatch: got=" + response + " want=" + expected);
        }
    }

    static Map<String, EvalResult> readResultFile(Path path) throws IOException {
        return readResultFileWithRetry(path, 1);
    }

    private static Map<String, EvalResult> readResultFileWithRetry(Path path, int attempts) throws IOException {
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return readResultFileOnce(path);
            } catch (IOException e) {
                last = e;
                if (i + 1 < attempts) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ie);
                    }
                }
            }
        }
        throw last;
    }

    private static Map<String, EvalResult> readResultFileOnce(Path path) throws IOException {
        Map<String, EvalResult> results = new HashMap<>();
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty() || !lines.get(0).equals(ResultWriter.HEADER)) {
            throw new IOException("invalid result header in " + path);
        }
        for (int i = 1; i < lines.size(); i++) {
            String[] row = CsvMasterSource.parseCsvLine(lines.get(i));
            if (row.length != 4) {
                throw new IOException("malformed result row in " + path);
            }
            if (row[0].isEmpty() || row[1].isEmpty() || !Loader.actionValid(row[3])) {
                throw new IOException("malformed result row in " + path);
            }
            results.put(row[0], new EvalResult(row[0], row[1], row[2].isEmpty() ? null : row[2], row[3]));
        }
        return results;
    }

    private static void verifyResultSet(Map<String, EvalResult> actual, Map<String, EvalResult> expected) throws IOException {
        if (actual.size() != expected.size()) {
            throw new IOException("result count mismatch: actual=" + actual.size() + " expected=" + expected.size());
        }
        for (Map.Entry<String, EvalResult> entry : expected.entrySet()) {
            EvalResult got = actual.get(entry.getKey());
            if (got == null || !sameResult(got, entry.getValue())) {
                throw new IOException("access " + entry.getKey() + " mismatch: got=" + got + " want=" + entry.getValue());
            }
        }
    }

    private static boolean sameResult(EvalResult a, EvalResult b) {
        String aMatched = a.matchedRuleId == null ? "" : a.matchedRuleId;
        String bMatched = b.matchedRuleId == null ? "" : b.matchedRuleId;
        return (a.accessId == null ? "" : a.accessId).equals(b.accessId)
                && (a.selectedPolicyId == null ? "" : a.selectedPolicyId).equals(b.selectedPolicyId)
                && aMatched.equals(bMatched)
                && (a.action == null ? "" : a.action).equals(b.action);
    }

    private static String trimEnd(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
