package firewall.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch evaluation as a single Spark SQL plan (accelerated by Gluten/Velox).
 *
 * <p>The plan mirrors the priority-indexed engine in
 * postgres-indexed-java-implementation:
 * <ol>
 *   <li>Parse {@code tenant_id}/{@code department_id} from the URL path.</li>
 *   <li>Select policy with department priority (no fallback to tenant when a
 *       department policy exists - rule 6).</li>
 *   <li>Select IP group scope independently (department set wins when any
 *       department group exists - rule 13).</li>
 *   <li>Collect candidate matches for each rule type, taking the minimum
 *       priority per access (= First Match).</li>
 *   <li>Emit {@code access_id, selected_policy_id, matched_rule_id, action}.</li>
 * </ol>
 *
 * <p>RLIKE is delegated to Velox's RE2 implementation when Gluten is active,
 * which matches the RE2 compatibility required by BENCHMARK_RULES.md section 11.
 */
final class BatchJob {
    private final SparkSession spark;

    BatchJob(SparkSession spark) {
        this.spark = spark;
    }

    /** Runs the full batch evaluation and writes results.csv. Returns the row count. */
    long run(String inputDirectory, String outputCsv) throws IOException {
        // Register access logs (Parquet) as a temp view.
        spark.read().parquet(inputDirectory).createOrReplaceTempView("access_logs");

        Dataset<Row> result = spark.sql(EVALUATION_SQL);

        // Materialize to a single CSV part file, then move it to the requested path.
        Path tempDir = Files.createTempDirectory("firewall-batch-");
        try {
            result.coalesce(1)
                    .write()
                    .mode("overwrite")
                    .option("header", "true")
                    .option("quote", "\"")
                    .option("nullValue", "")
                    .csv(tempDir.toString());

            Path partFile = findSinglePartFile(tempDir);
            Path target = Path.of(outputCsv);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);

            // Count rows (excluding header) for verification.
            try (var lines = Files.lines(target)) {
                return lines.count() - 1;
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static Path findSinglePartFile(Path directory) throws IOException {
        Path found = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "part-*.csv")) {
            for (Path p : stream) {
                if (found != null) {
                    throw new IOException("expected one part file but found multiple in " + directory);
                }
                found = p;
            }
        }
        if (found == null) {
            throw new IOException("no part file produced in " + directory);
        }
        return found;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path p : paths) {
            Files.deleteIfExists(p);
        }
    }

    /**
     * The full evaluation plan. Windowed ROW_NUMBER picks the lowest priority
     * candidate per access_id (= First Match). Spark/Gluten vectorizes the
     * RLIKE calls and the broadcast-friendly joins.
     */
    private static final String EVALUATION_SQL = """
            WITH access_enriched AS (
              SELECT
                access_id,
                source_ipv4,
                url_path,
                access_timestamp_utc,
                referer,
                user_agent,
                regexp_extract(url_path, '^/([^/]+)/([^/]+)/', 1) AS tenant_id,
                regexp_extract(url_path, '^/([^/]+)/([^/]+)/', 2) AS department_id,
                CAST(substring(access_timestamp_utc, 12, 2) AS INT) * 3600
                  + CAST(substring(access_timestamp_utc, 15, 2) AS INT) * 60
                  + CAST(substring(access_timestamp_utc, 18, 2) AS INT) AS access_time_sec
              FROM access_logs
            ),
            policy_selected AS (
              SELECT
                a.*,
                COALESCE(dp.policy_id, tp.policy_id) AS selected_policy_id,
                COALESCE(dp.default_action, tp.default_action) AS default_action,
                CASE WHEN dgs.tenant_id IS NOT NULL THEN 'department' ELSE 'tenant' END AS group_scope
              FROM access_enriched a
              LEFT JOIN policies dp
                ON dp.tenant_id = a.tenant_id
                AND dp.department_id = a.department_id
                AND dp.enabled
              LEFT JOIN policies tp
                ON tp.tenant_id = a.tenant_id
                AND tp.department_id = ''
                AND tp.enabled
              LEFT JOIN dept_group_scope dgs
                ON dgs.tenant_id = a.tenant_id
                AND dgs.department_id = a.department_id
            ),
            candidates AS (
              -- Regex-based rules
              SELECT ps.access_id, r.rule_id, r.priority, r.action
              FROM policy_selected ps
              JOIN firewall_rules r
                ON r.policy_id = ps.selected_policy_id
                AND r.enabled
                AND r.rule_type IN (
                    'SOURCE_IPV4_REGEX', 'URL_PATH_REGEX', 'REFERER_REGEX', 'USER_AGENT_REGEX'
                )
              WHERE
                (r.rule_type = 'SOURCE_IPV4_REGEX'
                  AND r.regex_pattern <> ''
                  AND ps.source_ipv4 RLIKE r.regex_pattern)
                OR (r.rule_type = 'URL_PATH_REGEX'
                  AND r.regex_pattern <> ''
                  AND ps.url_path RLIKE r.regex_pattern)
                OR (r.rule_type = 'REFERER_REGEX'
                  AND r.regex_pattern <> ''
                  AND COALESCE(ps.referer, '') RLIKE r.regex_pattern)
                OR (r.rule_type = 'USER_AGENT_REGEX'
                  AND r.regex_pattern <> ''
                  AND COALESCE(ps.user_agent, '') RLIKE r.regex_pattern)

              UNION ALL

              -- Time-range rules. Day-crossing ranges are represented as
              -- start_time_sec > end_time_sec and use OR semantics.
              SELECT ps.access_id, r.rule_id, r.priority, r.action
              FROM policy_selected ps
              JOIN firewall_rules r
                ON r.policy_id = ps.selected_policy_id
                AND r.enabled
                AND r.rule_type = 'ACCESS_TIME_RANGE'
              WHERE
                CASE
                  WHEN r.start_time_sec < r.end_time_sec THEN
                    ps.access_time_sec >= r.start_time_sec
                    AND ps.access_time_sec < r.end_time_sec
                  ELSE
                    ps.access_time_sec >= r.start_time_sec
                    OR ps.access_time_sec < r.end_time_sec
                END

              UNION ALL

              -- Source IPv4 group rules. The group set is selected per
              -- (tenant, department) scope, then joined to the rule's
              -- group_key and finally to the member rows.
              SELECT ps.access_id, r.rule_id, r.priority, r.action
              FROM policy_selected ps
              JOIN firewall_rules r
                ON r.policy_id = ps.selected_policy_id
                AND r.enabled
                AND r.rule_type = 'SOURCE_IPV4_GROUP'
              JOIN source_ip_groups g
                ON g.tenant_id = ps.tenant_id
                AND g.group_key = r.source_ip_group_key
                AND (
                  (ps.group_scope = 'department' AND g.department_id = ps.department_id)
                  OR
                  (ps.group_scope = 'tenant' AND g.department_id = '')
                )
              JOIN source_ip_group_members m
                ON m.group_id = g.group_id
                AND m.source_ipv4 = ps.source_ipv4
            ),
            first_match AS (
              SELECT
                access_id,
                rule_id AS matched_rule_id,
                action AS matched_action,
                priority AS matched_priority
              FROM (
                SELECT
                  access_id,
                  rule_id,
                  action,
                  priority,
                  ROW_NUMBER() OVER (PARTITION BY access_id ORDER BY priority ASC, rule_id ASC) AS rn
                FROM candidates
              )
              WHERE rn = 1
            )
            SELECT
              ps.access_id AS access_id,
              ps.selected_policy_id AS selected_policy_id,
              fm.matched_rule_id AS matched_rule_id,
              COALESCE(fm.matched_action, ps.default_action) AS action
            FROM policy_selected ps
            LEFT JOIN first_match fm ON fm.access_id = ps.access_id
            """;
}
