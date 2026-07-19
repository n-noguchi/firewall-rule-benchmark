package firewall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
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
        } catch (Throwable e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static int dispatch(String[] args) throws Exception {
        String command = args[0];
        String[] rest = shift(args);
        if ("serve".equals(command)) {
            return serve(rest);
        }
        if ("batch".equals(command)) {
            return batch(rest);
        }
        if ("batch-client".equals(command)) {
            return batchClient(rest);
        }
        if ("validate".equals(command)) {
            return validate(rest);
        }
        if ("health".equals(command)) {
            return health(rest);
        }
        if ("benchmark".equals(command)) {
            return benchmark(rest);
        }
        if ("verify-results".equals(command)) {
            return verifyResults(rest);
        }
        usage();
        System.err.println("unknown command " + command);
        return 2;
    }

    private static void usage() {
        System.err.println("usage: firewall-postgres-java8 <batch|batch-client|validate|serve|health|benchmark|verify-results> [flags]");
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
        waitForMaster(jdbcUrl(databaseUrl), waitTimeout);
        MasterStore store = MasterStore.open(jdbcUrl(databaseUrl), 8);
        store.ensureImported(Paths.get(data));
        Engine engine = store.loadEngine();
        final EngineHolder holder = new EngineHolder(engine);
        AdminService adminService = new AdminService(store, holder);
        InetSocketAddress address = parseAddress(listen, 8080);
        HttpServer server = HttpServer.create(address, 0);
        ServerHandler handler = new ServerHandler(holder);
        server.createContext("/health/ready", handler.ready());
        server.createContext("/v1/firewall/evaluate", handler.evaluate());
        server.createContext("/internal/run-batch", handler.runBatch(new ServerHandler.BatchRunner() {
            @Override
            public void run(String input, String output, Integer workers) throws Exception {
                Engine current = holder.get();
                java.util.List<AccessLog> logs = AccessLogReader.read(Paths.get(input));
                java.util.List<EvalResult> results = current.evaluateMany(logs, workers == null ? 0 : workers);
                ResultWriter.write(Paths.get(output), results);
            }
        }));
        server.createContext("/v1/admin/", new AdminHandler(adminService).handler());
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop(1);
                store.close();
            }
        }));
        System.err.println("listening on " + address);
        server.start();
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
        Map<String, EvalResult> actual = new LinkedHashMap<String, EvalResult>();
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
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("input", input);
        payload.put("output", output);
        if (workers != 0) payload.put("workers", Integer.valueOf(workers));
        byte[] body = Json.MAPPER.writeValueAsBytes(payload);
        int status = httpPostJson(trimEnd(server) + "/internal/run-batch", body);
        if (status != 200) {
            throw new IOException("batch server returned " + status);
        }
        return 0;
    }

    private static int health(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "server");
        String server = flags.value("server", "http://127.0.0.1:8080");
        URL url = new URL(trimEnd(server) + "/health/ready");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("not ready: " + status);
            }
            return 0;
        } finally {
            conn.disconnect();
        }
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
        java.io.File[] list = new java.io.File(results).listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(java.io.File dir, String name) {
                return name.matches("batch-run-.*\\.csv");
            }
        });
        if (list == null || list.length == 0) {
            throw new IOException("no batch-run-*.csv files in " + results);
        }
        java.util.List<Path> files = new java.util.ArrayList<Path>();
        for (java.io.File f : list) {
            files.add(f.toPath());
        }
        java.util.Collections.sort(files);
        for (Path path : files) {
            Map<String, EvalResult> actual = BenchmarkRunner.readResultFile(path);
            verifyResultSet(actual, expectedMap);
        }
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("files", Integer.valueOf(files.size()));
        report.put("rows", Long.valueOf((long) files.size() * (long) expectedMap.size()));
        report.put("all_correct", Boolean.TRUE);
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

    private static void waitForMaster(String jdbcUrl, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                Connection connection = DriverManager.getConnection(jdbcUrl);
                try {
                    Statement statement = connection.createStatement();
                    statement.execute("SELECT 1");
                    statement.close();
                } finally {
                    connection.close();
                }
                return;
            } catch (Throwable e) {
                lastError = e;
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }
        if (lastError == null) {
            throw new IllegalStateException("master DB unreachable");
        }
        throw new IllegalStateException("master DB not ready within " + timeout + ": " + lastError.getMessage(), lastError);
    }

    /** Sends a JSON POST and returns the HTTP status code. */
    static int httpPostJson(String url, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(0);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }
            // Drain the response body so the underlying socket can be reused by the keep-alive cache.
            InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                try {
                    byte[] buffer = new byte[4096];
                    while (is.read(buffer) > 0) {
                        // discard
                    }
                } catch (IOException ignored) {
                    // Connection closed by server is fine.
                } finally {
                    is.close();
                }
            }
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    /** Sends a JSON POST and returns the response body bytes. */
    static byte[] httpPostJsonBody(String url, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(0);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }
            InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (is != null) {
                try {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        baos.write(buffer, 0, n);
                    }
                } finally {
                    is.close();
                }
            }
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode() + " from " + url + ": " + new String(baos.toByteArray(), StandardCharsets.UTF_8));
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
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

    private static String getenvOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Tiny flag parser that knows about --key=value and --key value forms plus booleans. */
    static final class Flags {
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        static Flags parse(String[] args, String... knownKeys) {
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
            String v = values.get(key);
            return v == null ? fallback : v;
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

    @SuppressWarnings("unused")
    private static ObjectMapper mapper() {
        return Json.MAPPER;
    }
}
