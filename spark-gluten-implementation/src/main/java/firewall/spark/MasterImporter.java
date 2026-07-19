package firewall.spark;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Initial CSV -> PostgreSQL importer. Uses plain JDBC (no connection pool) to
 * avoid clashes with the HikariCP 2.5.1 jar that Spark ships in
 * $SPARK_HOME/jars. Import runs once on a fresh database volume; later boots
 * reuse the persisted data via the metadata.initialized flag.
 */
final class MasterImporter implements AutoCloseable {
    private final Connection connection;

    private MasterImporter(Connection connection) {
        this.connection = connection;
    }

    static MasterImporter open(String jdbcUrl, int maxPoolSizeIgnored) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        connection.setAutoCommit(true);
        MasterImporter importer = new MasterImporter(connection);
        importer.ensureSchema();
        return importer;
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    Connection borrowConnection() throws SQLException {
        return connection;
    }

    private void ensureSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(Schema.SCHEMA_DDL);
            statement.execute("INSERT INTO metadata(key, value) VALUES ('revision', '0') ON CONFLICT (key) DO NOTHING");
        }
    }

    /** Imports CSVs into PostgreSQL the first time the volume is used. Returns the data version. */
    String ensureImported(Path dataDirectory) throws IOException, SQLException, InterruptedException {
        String datasetVersion = Csv.readDatasetVersion(dataDirectory);
        ImportState state = readImportState();
        if (state.initialized) {
            if (!datasetVersion.equals(state.datasetVersion)) {
                throw new IOException("master DB contains dataset " + state.datasetVersion
                        + ", but input manifest is " + datasetVersion
                        + "; use a new database to preserve maintained data");
            }
            return state.dataVersion();
        }
        resetForImport(datasetVersion);
        importPolicies(dataDirectory);
        importRules(dataDirectory);
        importGroups(dataDirectory);
        importMembers(dataDirectory);
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO metadata(key, value) VALUES ('initialized', 'true') "
                    + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        }
        return datasetVersion;
    }

    String readDataVersion() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            String initialized = readMetadata(statement, "initialized");
            if (!"true".equals(initialized)) {
                throw new SQLException("master DB import is incomplete");
            }
            String dataset = readMetadata(statement, "dataset_version");
            String revisionText = readMetadata(statement, "revision");
            long revision = Long.parseLong(revisionText);
            return revision == 0 ? dataset : dataset + "+r" + revision;
        } catch (NumberFormatException e) {
            throw new SQLException("invalid revision", e);
        }
    }

    private record ImportState(boolean initialized, String datasetVersion) {
        String dataVersion() {
            return datasetVersion;
        }
    }

    private ImportState readImportState() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            String initialized = readMetadata(statement, "initialized");
            String dataset = readMetadata(statement, "dataset_version");
            return new ImportState("true".equals(initialized), dataset);
        }
    }

    private static String readMetadata(Statement statement, String key) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT value FROM metadata WHERE key = '" + key + "'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return "";
        }
    }

    private void resetForImport(String datasetVersion) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE policies, firewall_rules, source_ip_groups, source_ip_group_members");
            upsertMetadata(statement, "dataset_version", datasetVersion);
            upsertMetadata(statement, "revision", "0");
            upsertMetadata(statement, "initialized", "false");
        }
    }

    private static void upsertMetadata(Statement statement, String key, String value) throws SQLException {
        try (PreparedStatement ps = statement.getConnection().prepareStatement(
                "INSERT INTO metadata(key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void importPolicies(Path dataDirectory) throws IOException, SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO policies(policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            Csv.readDirectory(dataDirectory.resolve("policies"), Csv.Headers.POLICIES, row -> {
                try {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.setString(3, row[2]);
                    ps.setString(4, row[3]);
                    ps.setBoolean(5, parseBoolean(row[4]));
                    ps.setString(6, row[5]);
                    ps.setString(7, row[6]);
                    ps.addBatch();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            ps.executeBatch();
        }
    }

    private void importRules(Path dataDirectory) throws IOException, SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO firewall_rules(rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            Csv.readDirectory(dataDirectory.resolve("firewall_rules"), Csv.Headers.RULES, row -> {
                try {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.setInt(3, Integer.parseInt(row[2]));
                    ps.setString(4, row[3]);
                    ps.setString(5, row[4]);
                    ps.setBoolean(6, parseBoolean(row[5]));
                    ps.setString(7, row[6]);
                    ps.setString(8, row[7]);
                    ps.setString(9, row[8]);
                    ps.setString(10, row[9]);
                    ps.setString(11, row[10]);
                    ps.setString(12, row[11]);
                    ps.addBatch();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            ps.executeBatch();
        }
    }

    private void importGroups(Path dataDirectory) throws IOException, SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_ip_groups(group_id, tenant_id, department_id, group_key, name, created_at, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            Csv.readDirectory(dataDirectory.resolve("source_ip_groups"), Csv.Headers.GROUPS, row -> {
                try {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.setString(3, row[2]);
                    ps.setString(4, row[3]);
                    ps.setString(5, row[4]);
                    ps.setString(6, row[5]);
                    ps.setString(7, row[6]);
                    ps.addBatch();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            ps.executeBatch();
        }
    }

    private void importMembers(Path dataDirectory) throws IOException, SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_ip_group_members(group_id, source_ipv4, created_at, updated_at) VALUES (?,?,?,?)")) {
            Csv.readDirectory(dataDirectory.resolve("source_ip_group_members"), Csv.Headers.MEMBERS, row -> {
                try {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.setString(3, row[2]);
                    ps.setString(4, row[3]);
                    ps.addBatch();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            ps.executeBatch();
        }
    }

    private static boolean parseBoolean(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase();
        return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower);
    }
}
