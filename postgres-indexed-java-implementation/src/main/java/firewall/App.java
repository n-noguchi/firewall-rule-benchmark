package firewall;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Command dispatcher. Mirrors the Go priority-indexed CLI surface. */
public final class App {
    private App() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            int exit = dispatch(args);
            if (exit != 0) {
                System.exit(exit);
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static int dispatch(String[] args) throws Exception {
        String command = args[0];
        String[] rest = shift(args);
        switch (command) {
            case "serve" -> { return serve(rest); }
            case "batch" -> { return batch(rest); }
            case "batch-client" -> { return batchClient(rest); }
            case "validate" -> { return validate(rest); }
            case "health" -> { return health(rest); }
            case "benchmark" -> { return benchmark(rest); }
            case "verify-results" -> { return verifyResults(rest); }
            default -> {
                usage();
                System.err.println("unknown command " + command);
                return 2;
            }
        }
    }

    private static void usage() {
        System.err.println("usage: firewall-postgres-java <batch|batch-client|validate|serve|health|benchmark|verify-results> [flags]");
    }

    private static String[] shift(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    private static int serve(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "data", "database-url", "listen", "master-wait");
        String data = flags.value("data", "");
        String databaseUrl = flags.value("database-url", getenvOrDefault("MASTER_DATABASE_URL", ""));
        String listen = flags.value("listen", ":8080");
        Duration waitTimeout = Duration.parse(flags.value("master-wait", "PT2M"));
        if (data.isEmpty() || databaseUrl.isEmpty()) {
            throw new IllegalArgumentException("--data and --database-url (or MASTER_DATABASE_URL) are required");
        }
        waitForMaster(databaseUrl, waitTimeout);
        MasterStore store = MasterStore.open(jdbcUrl(databaseUrl), 8);
        store.ensureImported(Paths.get(data));
        Engine engine = store.loadEngine();
        EngineHolder holder = new EngineHolder(engine);
        AdminService adminService = new AdminService(store, holder);
        InetSocketAddress address = parseAddress(listen, 8080);
        HttpServer server = HttpServer.create(address, 0);
        ServerHandler handler = new ServerHandler(holder);
        server.createContext("/health/ready", handler.ready());
        server.createContext("/v1/firewall/evaluate", handler.evaluate());
        server.createContext("/internal/run-batch", handler.runBatch((input, output, workers) -> {
            Engine current = holder.get();
            java.util.List<AccessLog> logs = AccessLogReader.read(Paths.get(input));
            java.util.List<EvalResult> results = current.evaluateMany(logs, workers == null ? 0 : workers);
            ResultWriter.write(Paths.get(output), results);
        }));
        server.createContext("/v1/admin/", new AdminHandler(adminService).handler());
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            store.close();
        }));
        System.err.println("listening on " + address);
        server.start();
        // block indefinitely
        Thread.currentThread().join();
        return 0;
    }

    private static int batch(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "data", "input", "output", "workers");
        String data = flags.value("data", "");
        String input = flags.value("input", "");
        String output = flags.value("output", "");
        int workers = flags.intValue("workers", 0);
        if (data.isEmpty() || input.isEmpty() || output.isEmpty()) {
            throw new IllegalArgumentException("--data, --input and --output are required");
        }
        Engine engine = Loader.load(new CsvMasterSource(Paths.get(data)));
        return runBatch(engine, input, output, workers);
    }

    private static int runBatch(Engine engine, String input, String output, int workers) throws Exception {
        java.util.List<AccessLog> accesses = AccessLogReader.read(Paths.get(input));
        java.util.List<EvalResult> results = engine.evaluateMany(accesses, workers);
        ResultWriter.write(Paths.get(output), results);
        return 0;
    }

    private static int validate(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "data", "input", "expected", "workers");
        String data = flags.value("data", "");
        String input = flags.value("input", "");
        String expected = flags.value("expected", "");
        int workers = flags.intValue("workers", 0);
        if (data.isEmpty() || input.isEmpty() || expected.isEmpty()) {
            throw new IllegalArgumentException("--data, --input and --expected are required");
        }
        Engine engine = Loader.load(new CsvMasterSource(Paths.get(data)));
        java.util.List<AccessLog> accesses = AccessLogReader.read(Paths.get(input));
        java.util.List<EvalResult> results = engine.evaluateMany(accesses, workers);
        Map<String, EvalResult> actual = new LinkedHashMap<>();
        for (EvalResult result : results) {
            if (actual.put(result.accessId, result) != null) {
                throw new IOException("duplicate result access_id " + result.accessId);
            }
        }
        Map<String, EvalResult> expectedMap = ExpectedReader.read(Paths.get(expected));
        if (actual.size() != expectedMap.size()) {
            throw new IOException("result count mismatch: evaluated=" + actual.size() + " expected=" + expectedMap.size());
        }
        for (Map.Entry<String, EvalResult> entry : expectedMap.entrySet()) {
            EvalResult got = actual.get(entry.getKey());
            if (got == null || !sameResult(got, entry.getValue())) {
                throw new IOException("result mismatch for " + entry.getKey() + ": got=" + got + " want=" + entry.getValue());
            }
        }
        System.out.println("validated " + expectedMap.size() + " results");
        return 0;
    }

    private static boolean sameResult(EvalResult a, EvalResult b) {
        String aMatched = a.matchedRuleId == null ? "" : a.matchedRuleId;
        String bMatched = b.matchedRuleId == null ? "" : b.matchedRuleId;
        return a.accessId.equals(b.accessId) && a.selectedPolicyId.equals(b.selectedPolicyId)
                && aMatched.equals(bMatched) && a.action.equals(b.action);
    }

    private static int batchClient(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "server", "input", "output", "workers");
        String server = flags.value("server", "http://127.0.0.1:8080");
        String input = flags.value("input", "");
        String output = flags.value("output", "");
        int workers = flags.intValue("workers", 0);
        if (input.isEmpty() || output.isEmpty()) {
            throw new IllegalArgumentException("--input and --output are required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", input);
        payload.put("output", output);
        if (workers != 0) payload.put("workers", workers);
        byte[] body = Json.toBytes(payload);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimEnd(server) + "/internal/run-batch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("batch server returned " + response.statusCode());
        }
        return 0;
    }

    private static int health(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "server");
        String server = flags.value("server", "http://127.0.0.1:8080");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimEnd(server) + "/health/ready"))
                .timeout(Duration.ofSeconds(2))
                .GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("not ready: " + response.statusCode());
        }
        return 0;
    }

    private static int benchmark(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "mode", "input", "expected", "report", "warmup", "runs", "cooldown",
                "server", "batch-executable", "output-directory", "workers");
        String mode = flags.value("mode", "");
        String input = flags.value("input", "");
        String expected = flags.value("expected", "");
        String reportPath = flags.value("report", "");
        int warmup = flags.intValue("warmup", 3);
        int runs = flags.intValue("runs", 10);
        long cooldown = (long) (flags.doubleValue("cooldown", 0.2) * 1000);
        String serverUrl = flags.value("server", "http://127.0.0.1:8080");
        String outputDirectory = flags.value("output-directory", "");
        int workers = flags.intValue("workers", 0);
        if ("sequential".equals(mode) && expected.isEmpty()) {
            throw new IllegalArgumentException("--expected is required in sequential mode");
        }
        // batch mode posts to /internal/run-batch on the same server as sequential mode.
        return BenchmarkRunner.run(mode, input, expected, reportPath, warmup, runs, cooldown,
                serverUrl, serverUrl, outputDirectory, workers);
    }

    private static int verifyResults(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "expected", "results", "report");
        String expected = flags.value("expected", "");
        String results = flags.value("results", "");
        String reportPath = flags.value("report", "");
        if (expected.isEmpty() || results.isEmpty()) {
            throw new IllegalArgumentException("--expected and --results are required");
        }
        Map<String, EvalResult> expectedMap = ExpectedReader.read(Paths.get(expected));
        java.io.File[] list = new java.io.File(results).listFiles((d, name) -> name.matches("batch-run-.*\\.csv"));
        if (list == null || list.length == 0) {
            throw new IOException("no batch-run-*.csv files in " + results);
        }
        java.util.List<Path> files = new java.util.ArrayList<>();
        for (java.io.File f : list) {
            files.add(f.toPath());
        }
        java.util.Collections.sort(files);
        for (Path path : files) {
            Map<String, EvalResult> actual = BenchmarkRunner.readResultFile(path);
            verifyResultSet(actual, expectedMap);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("files", files.size());
        report.put("rows", files.size() * expectedMap.size());
        report.put("all_correct", true);
        report.put("completed_at", java.time.Instant.now().toString());
        byte[] json = Json.toBytes(report);
        if (!reportPath.isEmpty()) {
            Files.write(Paths.get(reportPath), json);
        }
        System.out.write(json);
        System.out.println();
        return 0;
    }

    private static void verifyResultSet(Map<String, EvalResult> actual, Map<String, EvalResult> expected) throws IOException {
        if (actual.size() != expected.size()) {
            throw new IOException("result count mismatch: actual=" + actual.size() + " expected=" + expected.size());
        }
        for (Map.Entry<String, EvalResult> entry : expected.entrySet()) {
            EvalResult got = actual.get(entry.getKey());
            if (got == null || !sameResult(got, entry.getValue())) {
                throw new IOException("access " + entry.getKey() + " mismatch");
            }
        }
    }

    private static void waitForMaster(String databaseUrl, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastError = null;
        while (System.nanoTime() < deadline) {
            try (ConnectionProbe probe = ConnectionProbe.probe(jdbcUrl(databaseUrl))) {
                return;
            } catch (Exception e) {
                lastError = e;
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }
        if (lastError == null) {
            throw new IllegalStateException("master DB unreachable");
        }
        throw new IllegalStateException("master DB not ready within " + timeout + ": " + lastError.getMessage(), lastError);
    }

    private static String getenvOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static InetSocketAddress parseAddress(String listen, int defaultPort) {
        String host;
        int port = defaultPort;
        if (listen.startsWith(":")) {
            host = "0.0.0.0";
            port = Integer.parseInt(listen.substring(1));
        } else if (listen.contains(":")) {
            int colon = listen.lastIndexOf(':');
            host = listen.substring(0, colon);
            port = Integer.parseInt(listen.substring(colon + 1));
        } else {
            host = listen;
        }
        return new InetSocketAddress(host, port);
    }

    private static String jdbcUrl(String databaseUrl) {
        if (databaseUrl.startsWith("postgres://")) {
            return databaseUrl.replaceFirst("^postgres://", "jdbc:postgresql://");
        }
        if (databaseUrl.startsWith("postgresql://")) {
            return databaseUrl.replaceFirst("^postgresql://", "jdbc:postgresql://");
        }
        return databaseUrl;
    }

    private static String trimEnd(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Tiny flag parser that knows about --key=value and --key value forms plus booleans. */
    static final class Flags {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        static Flags parse(String[] args, String... knownKeys) {
            java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(knownKeys));
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

        double doubleValue(String key, double fallback) {
            String value = values.get(key);
            if (value == null || value.isEmpty() || "true".equals(value)) {
                return fallback;
            }
            return Double.parseDouble(value);
        }
    }
}
