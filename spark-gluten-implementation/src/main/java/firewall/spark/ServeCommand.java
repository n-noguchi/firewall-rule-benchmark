package firewall.spark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Initialises Spark + Gluten, loads master data, then serves the batch HTTP API. */
final class ServeCommand {
    private ServeCommand() {
    }

    static int run(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "data", "database-url", "listen", "master-wait", "app-name",
                "driver-memory", "fetch-size");
        String data = flags.value("data", "");
        String databaseUrl = flags.value("database-url", getenvOrDefault("MASTER_DATABASE_URL", ""));
        String listen = flags.value("listen", ":8080");
        String appName = flags.value("app-name", "firewall-spark-gluten");
        Duration waitTimeout = Duration.parse(flags.value("master-wait", "PT2M"));
        int fetchSize = flags.intValue("fetch-size", 10000);

        if (data.isEmpty() || databaseUrl.isEmpty()) {
            throw new IllegalArgumentException("--data and --database-url (or MASTER_DATABASE_URL) are required");
        }

        // 1. Wait for PostgreSQL to answer.
        waitForMaster(databaseUrl, waitTimeout);

        // 2. Import CSVs into PostgreSQL (only first run on a fresh volume).
        String dataVersion;
        try (MasterImporter importer = MasterImporter.open(databaseUrl, 8)) {
            dataVersion = importer.ensureImported(Paths.get(data));
        }

        // 3. Build SparkSession with Gluten plugin + Velox backend.
        SparkSession spark = SparkFactory.create(appName);
        spark.sparkContext().setLogLevel("WARN");

        // 4. Read master tables via Spark JDBC, cache them, register temp views.
        MasterLoader loader = new MasterLoader(spark, databaseUrl, fetchSize);
        Map<String, Long> counts = loader.loadAndCache();
        System.err.println("master loaded: " + counts);

        // 5. Start HTTP server with the READY + run-batch endpoints.
        AtomicReference<String> liveDataVersion = new AtomicReference<>(dataVersion);
        BatchJob job = new BatchJob(spark);
        InetSocketAddress address = parseAddress(listen, 8080);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/health/ready", readyHandler(liveDataVersion));
        server.createContext("/internal/run-batch", runBatchHandler(job));
        server.createContext("/v1/firewall/evaluate", notImplemented("sequential mode is out of scope for this implementation"));
        server.createContext("/v1/admin/", notImplemented("admin CRUD is out of scope for this implementation; edit PostgreSQL directly"));
        server.setExecutor(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            spark.stop();
        }));
        server.start();
        System.err.println("listening on " + address + ", data_version=" + dataVersion + ", gluten=enabled");
        Thread.currentThread().join();
        return 0;
    }

    private static HttpHandler readyHandler(AtomicReference<String> dataVersion) {
        return exchange -> {
            try (exchange) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "READY");
                body.put("data_version", dataVersion.get());
                writeJson(exchange, 200, body);
            }
        };
    }

    private static HttpHandler runBatchHandler(BatchJob job) {
        return exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, Json.error("method not allowed"));
                    return;
                }
                BatchRequest request;
                try {
                    request = Json.parse(exchange.getRequestBody(), BatchRequest.class);
                } catch (IOException e) {
                    writeJson(exchange, 400, Json.error(e.getMessage()));
                    return;
                }
                if (request.input == null || request.input.isEmpty()
                        || request.output == null || request.output.isEmpty()) {
                    writeJson(exchange, 400, Json.error("input and output are required"));
                    return;
                }
                try {
                    long rows = job.run(request.input, request.output);
                    Map<String, Object> body = Json.status("complete");
                    body.put("rows", rows);
                    writeJson(exchange, 200, body);
                } catch (Throwable t) {
                    t.printStackTrace(System.err);
                    writeJson(exchange, 500, Json.error(t.toString()));
                }
            }
        };
    }

    private static HttpHandler notImplemented(String message) {
        return exchange -> {
            try (exchange) {
                writeJson(exchange, 501, Json.error(message));
            }
        };
    }

    private static void waitForMaster(String databaseUrl, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastError = null;
        while (System.nanoTime() < deadline) {
            try (java.sql.Connection ignored = java.sql.DriverManager.getConnection(databaseUrl)) {
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

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = Json.toBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
            output.flush();
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

    private static String getenvOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @SuppressWarnings("unused")
    private static Path path(String first, String... more) {
        return Path.of(first, more);
    }

    public static final class BatchRequest {
        public String input;
        public String output;
    }
}
