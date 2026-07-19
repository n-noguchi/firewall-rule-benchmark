package firewall;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Replicates the benchmark runner used by the Go implementations. Talks to
 * /internal/run-batch for the batch division and walks a Keep-Alive HTTP/1.1
 * connection for the sequential division. Uses HttpURLConnection so it stays
 * Java 8 compatible.
 */
final class BenchmarkRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BenchmarkRunner() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class Report {
        @JsonProperty("mode") String mode;
        @JsonProperty("started_at") String startedAt;
        @JsonProperty("completed_at") String completedAt;
        @JsonProperty("warmup_runs") int warmupRuns;
        @JsonProperty("measured_runs") int measuredRuns;
        @JsonProperty("access_count") int accessCount;
        @JsonProperty("validation_performed") boolean validationPerformed;
        @JsonProperty("all_correct") boolean allCorrect;
        @JsonProperty("batch") List<BatchRun> batch;
        @JsonProperty("sequential") List<SequentialRun> sequential;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class BatchRun {
        @JsonProperty("run") int run;
        @JsonProperty("elapsed_ns") long elapsedNs;
        @JsonProperty("validated") boolean validated;
        @JsonProperty("correct") boolean correct;

        BatchRun() {
        }

        BatchRun(int run, long elapsedNs, boolean validated, boolean correct) {
            this.run = run;
            this.elapsedNs = elapsedNs;
            this.validated = validated;
            this.correct = correct;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class SequentialRun {
        @JsonProperty("run") int run;
        @JsonProperty("total_ns") long totalNs;
        @JsonProperty("p50_ns") long p50Ns;
        @JsonProperty("p99_ns") long p99Ns;
        @JsonProperty("maximum_ns") long maximumNs;
        @JsonProperty("correct") boolean correct;

        SequentialRun() {
        }

        SequentialRun(int run, long totalNs, long p50Ns, long p99Ns, long maximumNs, boolean correct) {
            this.run = run;
            this.totalNs = totalNs;
            this.p50Ns = p50Ns;
            this.p99Ns = p99Ns;
            this.maximumNs = maximumNs;
            this.correct = correct;
        }
    }

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
        Report report = new Report();
        report.mode = mode;
        report.startedAt = startedAt;
        report.warmupRuns = warmup;
        report.measuredRuns = runs;
        report.accessCount = accesses.size();
        report.validationPerformed = expected != null;
        report.allCorrect = expected != null;
        List<BatchRun> batchRuns = null;
        List<SequentialRun> seqRuns = null;
        if ("batch".equals(mode)) {
            if (outputDirectory == null || outputDirectory.isEmpty()) {
                throw new IllegalArgumentException("--output-directory is required in batch mode");
            }
            batchRuns = measureBatch(serverUrl, input, outputDirectory, expected, workers, warmup, runs, cooldownMs);
        } else {
            seqRuns = measureSequential(serverUrl, accesses, expected, warmup, runs, cooldownMs);
        }
        report.batch = batchRuns;
        report.sequential = seqRuns;
        report.completedAt = java.time.Instant.now().toString();
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

    private static List<BatchRun> measureBatch(String serverUrl, String input, String outputDirectory,
                                               Map<String, EvalResult> expected, int workers, int warmup, int runs, long cooldownMs) throws Exception {
        Files.createDirectories(Paths.get(outputDirectory));
        int totalRuns = warmup + runs;
        List<BatchRun> measurements = new ArrayList<BatchRun>(runs);
        String endpoint = trimEnd(serverUrl) + "/internal/run-batch";
        for (int iteration = 0; iteration < totalRuns; iteration++) {
            Path output = Paths.get(outputDirectory, String.format("batch-run-%03d.csv", iteration + 1));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("input", input);
            payload.put("output", output.toString());
            if (workers != 0) {
                payload.put("workers", Integer.valueOf(workers));
            }
            byte[] body = MAPPER.writeValueAsBytes(payload);
            long start = System.nanoTime();
            int status = App.httpPostJson(endpoint, body);
            long elapsed = System.nanoTime() - start;
            if (status != 200) {
                throw new IOException("batch iteration " + (iteration + 1) + " failed: status=" + status);
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
        List<byte[]> payloads = new ArrayList<byte[]>(accesses.size());
        for (AccessLog access : accesses) {
            payloads.add(MAPPER.writeValueAsBytes(access));
        }
        String endpoint = trimEnd(serverUrl) + "/v1/firewall/evaluate";
        int totalRuns = warmup + runs;
        List<SequentialRun> measurements = new ArrayList<SequentialRun>(runs);
        // Keepalive: HttpURLConnection reuses the JDK keep-alive cache as long as we fully drain
        // each response and do not call disconnect. We do that in App.httpPostJsonBody.
        for (int iteration = 0; iteration < totalRuns; iteration++) {
            long[] latencies = new long[accesses.size()];
            long total = 0;
            for (int index = 0; index < accesses.size(); index++) {
                long start = System.nanoTime();
                byte[] responseBody = App.httpPostJsonBody(endpoint, payloads.get(index));
                long elapsed = System.nanoTime() - start;
                verifySequentialResponse(responseBody, accesses.get(index).accessId,
                        expected == null ? null : expected.get(accesses.get(index).accessId));
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
        Map<String, EvalResult> results = new HashMap<String, EvalResult>();
        List<String> lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
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
        String aId = a.accessId == null ? "" : a.accessId;
        String aPolicy = a.selectedPolicyId == null ? "" : a.selectedPolicyId;
        String aAction = a.action == null ? "" : a.action;
        return aId.equals(b.accessId)
                && aPolicy.equals(b.selectedPolicyId)
                && aMatched.equals(bMatched)
                && aAction.equals(b.action);
    }

    private static String trimEnd(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
