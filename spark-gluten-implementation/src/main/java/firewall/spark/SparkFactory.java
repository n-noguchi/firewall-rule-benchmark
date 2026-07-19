package firewall.spark;

import org.apache.spark.sql.SparkSession;

/** Common Spark + Gluten + Velox configuration shared by serve/batch commands. */
final class SparkFactory {
    private SparkFactory() {
    }

    static SparkSession create(String appName) {
        // GLUTEN_ENABLED=0 (or unset "false"/"0") falls back to vanilla Spark,
        // which still runs the same SQL plan without the Velox backend. Useful
        // when the runtime image doesn't have the native libs Netty requires.
        String glutenEnv = System.getenv().getOrDefault("GLUTEN_ENABLED", "true");
        boolean glutenEnabled = !"0".equals(glutenEnv) && !"false".equalsIgnoreCase(glutenEnv);

        SparkSession.Builder builder = SparkSession.builder()
                .appName(appName)
                .master("local[*]")
                // Keep UTC so hour()/minute()/second() and timestamp parsing are deterministic.
                .config("spark.sql.session.timeZone", "UTC")
                // Small dataset → few partitions; the default 200 would over-fragment 25k rows.
                .config("spark.sql.shuffle.partitions", "8")
                .config("spark.default.parallelism", "8")
                .config("spark.sql.adaptive.enabled", "true")
                .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        if (glutenEnabled) {
            // Gluten/Velox requires off-heap memory for native columnar buffers.
            builder.config("spark.memory.offHeap.enabled", "true")
                    .config("spark.memory.offHeap.size", "4g")
                    .config("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
                    // Gluten + Velox backend.
                    .config("spark.plugins", "org.apache.gluten.GlutenPlugin")
                    .config("spark.gluten.sql.enable", "true")
                    .config("spark.gluten.sql.columnar.columnarToRow.enabled", "true")
                    .config("spark.gluten.sql.columnar.rowToColumnar.enabled", "true")
                    // libvelox.so / libgluten.so and the Boost 1.84 / system libs are
                    // installed on the host (see Dockerfile) and registered via
                    // ldconfig. Disabling in-JAR loading avoids Gluten's expectation
                    // that every transitive .so is also bundled in a JAR.
                    .config("spark.gluten.loadLibFromJar", "false");
        }

        return builder.getOrCreate();
    }
}
