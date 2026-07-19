package firewall.spark;

import org.apache.spark.sql.SparkSession;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** CLI dispatcher. Mirrors the postgres-indexed-java command surface. */
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
            case "serve" -> { return ServeCommand.run(rest); }
            case "batch" -> { return batch(rest); }
            case "batch-client" -> { return batchClient(rest); }
            case "validate" -> { return validate(rest); }
            case "health" -> { return health(rest); }
            default -> {
                usage();
                System.err.println("unknown command " + command);
                return 2;
            }
        }
    }

    private static void usage() {
        System.err.println("usage: firewall-spark-gluten <serve|batch|batch-client|validate|health> [flags]");
    }

    private static String[] shift(String[] args) {
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    /** Runs the batch directly without the HTTP server (useful for one-shot validation). */
    private static int batch(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "data", "database-url", "input", "output", "app-name", "fetch-size");
        String data = flags.value("data", "");
        String databaseUrl = flags.value("database-url", getenvOrDefault("MASTER_DATABASE_URL", ""));
        String input = flags.value("input", "");
        String output = flags.value("output", "");
        String appName = flags.value("app-name", "firewall-spark-gluten-batch");
        int fetchSize = flags.intValue("fetch-size", 10000);
        if (data.isEmpty() || databaseUrl.isEmpty() || input.isEmpty() || output.isEmpty()) {
            throw new IllegalArgumentException("--data, --database-url, --input and --output are required");
        }

        try (MasterImporter importer = MasterImporter.open(databaseUrl, 8)) {
            importer.ensureImported(Paths.get(data));
        }

        SparkSession spark = SparkFactory.create(appName);
        spark.sparkContext().setLogLevel("WARN");

        try {
            MasterLoader loader = new MasterLoader(spark, databaseUrl, fetchSize);
            loader.loadAndCache();
            BatchJob job = new BatchJob(spark);
            long rows = job.run(input, output);
            System.err.println("batch complete: " + rows + " rows -> " + output);
            return 0;
        } finally {
            spark.stop();
        }
    }

    /** Direct batch run followed by full diff against expected_results. */
    private static int validate(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "data", "database-url", "input", "expected", "output", "app-name", "fetch-size");
        String data = flags.value("data", "");
        String databaseUrl = flags.value("database-url", getenvOrDefault("MASTER_DATABASE_URL", ""));
        String input = flags.value("input", "");
        String expected = flags.value("expected", "");
        String output = flags.value("output", "");
        String appName = flags.value("app-name", "firewall-spark-gluten-validate");
        int fetchSize = flags.intValue("fetch-size", 10000);
        if (data.isEmpty() || databaseUrl.isEmpty() || input.isEmpty() || expected.isEmpty()) {
            throw new IllegalArgumentException("--data, --database-url, --input and --expected are required");
        }
        Path tempOutput = output.isEmpty()
                ? Files.createTempFile("firewall-validate-", ".csv")
                : Paths.get(output);

        batch(new String[]{
                "--data", data,
                "--database-url", databaseUrl,
                "--input", input,
                "--output", tempOutput.toString(),
                "--app-name", appName,
                "--fetch-size", Integer.toString(fetchSize),
        });

        ResultVerifier.verify(Paths.get(expected), tempOutput);
        return 0;
    }

    private static int batchClient(String[] args) throws Exception {
        Flags flags = Flags.parse(args, "server", "input", "output");
        String server = flags.value("server", "http://127.0.0.1:8080");
        String input = flags.value("input", "");
        String output = flags.value("output", "");
        if (input.isEmpty() || output.isEmpty()) {
            throw new IllegalArgumentException("--input and --output are required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", input);
        payload.put("output", output);
        byte[] body = Json.toBytes(payload);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimEnd(server) + "/internal/run-batch"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofMinutes(10))
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

    private static String getenvOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String trimEnd(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
