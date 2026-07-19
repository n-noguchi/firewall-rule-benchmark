package firewall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PostgreSQL-backed master store. Owns its own HikariCP pool and implements
 * {@link MasterSource} so loaders can consume rows uniformly. Mutations run
 * inside a transaction that also rebuilds the engine so an invalid master
 * never replaces the live snapshot.
 */
final class MasterStore implements AutoCloseable, MasterSource {
    static final String POLICIES_TABLE = "policies";
    static final String RULES_TABLE = "firewall_rules";
    static final String GROUPS_TABLE = "source_ip_groups";
    static final String MEMBERS_TABLE = "source_ip_group_members";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HikariDataSource pool;
    private final ReentrantLock mutationLock = new ReentrantLock();

    private MasterStore(HikariDataSource pool) {
        this.pool = pool;
    }

    static MasterStore open(String jdbcUrl, int maxPoolSize) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(1);
        config.setIdleTimeout(15 * 60 * 1000);
        config.setMaxLifetime(60 * 60 * 1000);
        config.setConnectionTimeout(30_000);
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("prepareThreshold", "1");
        HikariDataSource pool = new HikariDataSource(config);
        MasterStore store = new MasterStore(pool);
        store.ensureSchema();
        return store;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }

    /** Borrows a connection from the underlying pool. */
    Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    private void ensureSchema() throws SQLException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA_DDL);
            statement.execute("INSERT INTO metadata(key, value) VALUES ('revision', '0') ON CONFLICT (key) DO NOTHING");
        }
    }

    private static final String SCHEMA_DDL = """
            CREATE TABLE IF NOT EXISTS metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS policies (
                policy_id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                department_id TEXT NOT NULL DEFAULT '',
                default_action TEXT NOT NULL,
                enabled BOOLEAN NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS firewall_rules (
                rule_id TEXT PRIMARY KEY,
                policy_id TEXT NOT NULL,
                priority INTEGER NOT NULL,
                rule_type TEXT NOT NULL,
                action TEXT NOT NULL,
                enabled BOOLEAN NOT NULL,
                regex_pattern TEXT NOT NULL DEFAULT '',
                source_ip_group_key TEXT NOT NULL DEFAULT '',
                start_time_utc TEXT NOT NULL DEFAULT '',
                end_time_utc TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS source_ip_groups (
                group_id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                department_id TEXT NOT NULL DEFAULT '',
                group_key TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS source_ip_group_members (
                group_id TEXT NOT NULL,
                source_ipv4 TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY (group_id, source_ipv4)
            );
            """;

    void ensureImported(Path dataDirectory) throws IOException, SQLException, InterruptedException {
        String datasetVersion = Loader.readDatasetVersion(dataDirectory);
        ImportState state = readImportState();
        if (state.initialized) {
            if (!datasetVersion.equals(state.datasetVersion)) {
                throw new IOException("master DB contains dataset " + state.datasetVersion
                        + ", but input manifest is " + datasetVersion
                        + "; use a new database to preserve maintained data");
            }
            return;
        }
        resetForImport(datasetVersion);
        CsvMasterSource source = new CsvMasterSource(dataDirectory);
        importPolicies(source);
        importRules(source);
        importGroups(source);
        importMembers(source);
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO metadata(key, value) VALUES ('initialized', 'true') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        }
    }

    private record ImportState(boolean initialized, String datasetVersion) {}

    private ImportState readImportState() throws SQLException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
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
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
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

    private void importPolicies(CsvMasterSource source) throws IOException, SQLException, InterruptedException {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO policies(policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            source.readPolicies(row -> {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.setString(3, row[2]);
                ps.setString(4, row[3]);
                ps.setBoolean(5, Loader.parseBoolean(row[4]));
                ps.setString(6, row[5]);
                ps.setString(7, row[6]);
                ps.addBatch();
            });
            ps.executeBatch();
        }
    }

    private void importRules(CsvMasterSource source) throws IOException, SQLException, InterruptedException {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO firewall_rules(rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            source.readRules(row -> {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.setInt(3, Integer.parseInt(row[2]));
                ps.setString(4, row[3]);
                ps.setString(5, row[4]);
                ps.setBoolean(6, Loader.parseBoolean(row[5]));
                ps.setString(7, row[6]);
                ps.setString(8, row[7]);
                ps.setString(9, row[8]);
                ps.setString(10, row[9]);
                ps.setString(11, row[10]);
                ps.setString(12, row[11]);
                ps.addBatch();
            });
            ps.executeBatch();
        }
    }

    private void importGroups(CsvMasterSource source) throws IOException, SQLException, InterruptedException {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO source_ip_groups(group_id, tenant_id, department_id, group_key, name, created_at, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            source.readGroups(row -> {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.setString(3, row[2]);
                ps.setString(4, row[3]);
                ps.setString(5, row[4]);
                ps.setString(6, row[5]);
                ps.setString(7, row[6]);
                ps.addBatch();
            });
            ps.executeBatch();
        }
    }

    private void importMembers(CsvMasterSource source) throws IOException, SQLException, InterruptedException {
        try (Connection connection = pool.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO source_ip_group_members(group_id, source_ipv4, created_at, updated_at) VALUES (?,?,?,?)")) {
            source.readMembers(row -> {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.setString(3, row[2]);
                ps.setString(4, row[3]);
                ps.addBatch();
            });
            ps.executeBatch();
        }
    }

    @Override
    public String dataVersion() throws SQLException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            String initialized = readMetadata(statement, "initialized");
            if (!"true".equals(initialized)) {
                throw new SQLException("master DB import is incomplete");
            }
            String dataset = readMetadata(statement, "dataset_version");
            String revisionText = readMetadata(statement, "revision");
            long revision = Long.parseLong(revisionText);
            if (revision == 0) {
                return dataset;
            }
            return dataset + "+r" + revision;
        } catch (NumberFormatException e) {
            throw new SQLException("invalid revision", e);
        }
    }

    @Override
    public void readPolicies(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at FROM policies")) {
            while (rs.next()) {
                consumer.accept(new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), Boolean.toString(rs.getBoolean(5)),
                        rs.getString(6), rs.getString(7)
                });
            }
        }
    }

    @Override
    public void readRules(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at FROM firewall_rules")) {
            while (rs.next()) {
                consumer.accept(new String[]{
                        rs.getString(1), rs.getString(2), Integer.toString(rs.getInt(3)),
                        rs.getString(4), rs.getString(5), Boolean.toString(rs.getBoolean(6)),
                        rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12)
                });
            }
        }
    }

    @Override
    public void readGroups(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT group_id, tenant_id, department_id, group_key, name, created_at, updated_at FROM source_ip_groups")) {
            while (rs.next()) {
                consumer.accept(new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)
                });
            }
        }
    }

    @Override
    public void readMembers(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT group_id, source_ipv4, created_at, updated_at FROM source_ip_group_members")) {
            while (rs.next()) {
                consumer.accept(new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)
                });
            }
        }
    }

    Engine loadEngine() throws IOException, SQLException, InterruptedException {
        String dataVersion = dataVersion();
        Engine engine = Loader.load(this);
        return engine.withDataVersion(dataVersion);
    }

    /**
     * Runs the mutation spec inside a transaction, bumps the revision, validates
     * the resulting engine by reloading inside the same transaction, commits,
     * then returns the rebuilt engine. Validation failures roll the transaction
     * back and the caller receives the exception.
     */
    Engine runMutation(MutationSpec spec) throws Exception {
        mutationLock.lock();
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (spec.remove) {
                    if (!rowExists(connection, spec.table, spec.keys, spec.keyArgs)) {
                        throw new MasterNotFoundException();
                    }
                    try (PreparedStatement ps = connection.prepareStatement(deleteSql(spec.table, spec.keys))) {
                        for (int i = 0; i < spec.keyArgs.length; i++) {
                            ps.setObject(i + 1, spec.keyArgs[i]);
                        }
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement(upsertSql(spec.table, spec.columns, spec.keys))) {
                        for (int i = 0; i < spec.args.length; i++) {
                            ps.setObject(i + 1, spec.args[i]);
                        }
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE metadata SET value = (CAST(value AS BIGINT) + 1)::TEXT WHERE key = 'revision'")) {
                    ps.executeUpdate();
                }
                Engine engine = loadEngineInTransaction(connection);
                connection.commit();
                return engine;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } finally {
            mutationLock.unlock();
        }
    }

    private static Engine loadEngineInTransaction(Connection connection) throws IOException, SQLException, InterruptedException {
        // Use a Savepoint-free read of the in-transaction rows. We must run on the same connection
        // so that the validation reflects the post-mutation state.
        TxMasterSource source = new TxMasterSource(connection);
        String dataVersion = source.dataVersion();
        Engine engine = Loader.load(source);
        return engine.withDataVersion(dataVersion);
    }

    private static boolean rowExists(Connection connection, String table, String[] keys, Object[] keyArgs) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE " + whereClause(keys, 1) + ")")) {
            for (int i = 0; i < keyArgs.length; i++) {
                ps.setObject(i + 1, keyArgs[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static String deleteSql(String table, String[] keys) {
        return "DELETE FROM " + table + " WHERE " + whereClause(keys, 1);
    }

    private static String upsertSql(String table, String[] columns, String[] keys) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append("$").append(i + 1);
        }
        String conflict = String.join(", ", keys);
        StringBuilder update = new StringBuilder();
        for (String column : columns) {
            if (contains(keys, column)) continue;
            if (update.length() > 0) update.append(", ");
            update.append(column).append(" = EXCLUDED.").append(column);
        }
        String cols = String.join(", ", columns);
        if (update.length() == 0) {
            return "INSERT INTO " + table + "(" + cols + ") VALUES (" + placeholders + ") ON CONFLICT DO NOTHING";
        }
        return "INSERT INTO " + table + "(" + cols + ") VALUES (" + placeholders + ") ON CONFLICT (" + conflict + ") DO UPDATE SET " + update;
    }

    private static String whereClause(String[] keys, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(keys[i]).append(" = $").append(start + i);
        }
        return sb.toString();
    }

    private static boolean contains(String[] keys, String target) {
        for (String key : keys) {
            if (key.equals(target)) return true;
        }
        return false;
    }

    static final class MutationSpec {
        final String table;
        final String[] columns;
        final Object[] args;
        final String[] keys;
        final Object[] keyArgs;
        final boolean remove;

        MutationSpec(String table, String[] columns, Object[] args, String[] keys, Object[] keyArgs, boolean remove) {
            this.table = table;
            this.columns = columns;
            this.args = args;
            this.keys = keys;
            this.keyArgs = keyArgs;
            this.remove = remove;
        }
    }

    static final class MasterNotFoundException extends RuntimeException {
    }

    /**
     * {@link MasterSource} backed by an in-flight transaction so admin
     * mutations can validate their post-mutation snapshot before commit.
     */
    private static final class TxMasterSource implements MasterSource {
        private final Connection connection;

        TxMasterSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public String dataVersion() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                String initialized = readMetadata(statement, "initialized");
                if (!"true".equals(initialized)) {
                    throw new SQLException("master DB import is incomplete");
                }
                String dataset = readMetadata(statement, "dataset_version");
                String revisionText = readMetadata(statement, "revision");
                long revision = Long.parseLong(revisionText);
                return revision == 0 ? dataset : dataset + "+r" + revision;
            }
        }

        @Override
        public void readPolicies(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at FROM policies")) {
                while (rs.next()) {
                    consumer.accept(new String[]{
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), Boolean.toString(rs.getBoolean(5)),
                            rs.getString(6), rs.getString(7)
                    });
                }
            }
        }

        @Override
        public void readRules(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at FROM firewall_rules")) {
                while (rs.next()) {
                    consumer.accept(new String[]{
                            rs.getString(1), rs.getString(2), Integer.toString(rs.getInt(3)),
                            rs.getString(4), rs.getString(5), Boolean.toString(rs.getBoolean(6)),
                            rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getString(11), rs.getString(12)
                    });
                }
            }
        }

        @Override
        public void readGroups(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT group_id, tenant_id, department_id, group_key, name, created_at, updated_at FROM source_ip_groups")) {
                while (rs.next()) {
                    consumer.accept(new String[]{
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)
                    });
                }
            }
        }

        @Override
        public void readMembers(RowConsumer consumer) throws SQLException, IOException, InterruptedException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT group_id, source_ipv4, created_at, updated_at FROM source_ip_group_members")) {
                while (rs.next()) {
                    consumer.accept(new String[]{
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)
                    });
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private static ObjectMapper mapper() {
        return MAPPER;
    }
}
