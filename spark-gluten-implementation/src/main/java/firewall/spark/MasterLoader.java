package firewall.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads master tables from PostgreSQL into Spark via JDBC, derives convenience
 * columns used by the evaluation SQL (time-of-day in seconds), materializes
 * each DataFrame with {@link Dataset#cache()} and registers them as temp
 * views so the Spark SQL engine (accelerated by Gluten/Velox) can scan them
 * without going back to PostgreSQL during the measured batch run.
 */
final class MasterLoader {
    private final SparkSession spark;
    private final String jdbcUrl;
    private final int fetchSize;

    MasterLoader(SparkSession spark, String jdbcUrl, int fetchSize) {
        this.spark = spark;
        this.jdbcUrl = jdbcUrl;
        this.fetchSize = fetchSize;
    }

    /** Loads + caches the master views. Returns row counts per table for diagnostics. */
    Map<String, Long> loadAndCache() throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();

        Dataset<Row> policies = readTable(Schema.POLICIES_TABLE,
                "policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at");
        policies.createOrReplaceTempView("policies");
        counts.put("policies", policies.cache().count());

        Dataset<Row> rules = readTable(Schema.RULES_TABLE,
                "rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, "
                        + "source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at");
        // Pre-compute start/end time-of-day in seconds for ACCESS_TIME_RANGE so the
        // evaluation SQL can do plain integer comparison without re-parsing per row.
        Dataset<Row> rulesPrepared = rules
                .withColumn("start_time_sec",
                        org.apache.spark.sql.functions.expr(
                                "(CASE WHEN start_time_utc = '' THEN -1 "
                                        + "ELSE substring(start_time_utc, 1, 2) * 3600 "
                                        + "+ substring(start_time_utc, 4, 2) * 60 "
                                        + "+ substring(start_time_utc, 7, 2) END)"))
                .withColumn("end_time_sec",
                        org.apache.spark.sql.functions.expr(
                                "(CASE WHEN end_time_utc = '' THEN -1 "
                                        + "ELSE substring(end_time_utc, 1, 2) * 3600 "
                                        + "+ substring(end_time_utc, 4, 2) * 60 "
                                        + "+ substring(end_time_utc, 7, 2) END)"));
        rulesPrepared.createOrReplaceTempView("firewall_rules");
        counts.put("firewall_rules", rulesPrepared.cache().count());

        Dataset<Row> groups = readTable(Schema.GROUPS_TABLE,
                "group_id, tenant_id, department_id, group_key, name, created_at, updated_at");
        groups.createOrReplaceTempView("source_ip_groups");
        counts.put("source_ip_groups", groups.cache().count());

        Dataset<Row> members = readTable(Schema.MEMBERS_TABLE,
                "group_id, source_ipv4, created_at, updated_at");
        members.createOrReplaceTempView("source_ip_group_members");
        counts.put("source_ip_group_members", members.cache().count());

        // Build the per-(tenant, department) "has any group" flag used by IP-group
        // scope selection (rule 13: department set wins if any department group exists).
        Dataset<Row> deptGroupScope = spark.sql(
                "SELECT DISTINCT tenant_id, department_id FROM source_ip_groups WHERE department_id <> ''");
        deptGroupScope.createOrReplaceTempView("dept_group_scope");
        counts.put("dept_group_scope", deptGroupScope.cache().count());

        return counts;
    }

    private Dataset<Row> readTable(String table, String select) {
        return spark.read()
                .format("jdbc")
                .option("url", jdbcUrl)
                .option("fetchsize", Integer.toString(fetchSize))
                .option("driver", "org.postgresql.Driver")
                .option("dbtable", "(SELECT " + select + " FROM " + table + ") AS " + table + "_v")
                .load();
    }

    /** Updates the metadata revision stamp; used when master CRUD becomes available. */
    void bumpRevision(MasterImporter importer) throws SQLException {
        try (Connection connection = importer.borrowConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE metadata SET value = (CAST(value AS BIGINT) + 1)::TEXT WHERE key = 'revision'")) {
            ps.executeUpdate();
        }
    }
}
