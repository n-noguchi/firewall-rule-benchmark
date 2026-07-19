package firewall.spark;

/** PostgreSQL master schema. Mirrors postgres-indexed-java-implementation. */
final class Schema {
    private Schema() {
    }

    static final String POLICIES_TABLE = "policies";
    static final String RULES_TABLE = "firewall_rules";
    static final String GROUPS_TABLE = "source_ip_groups";
    static final String MEMBERS_TABLE = "source_ip_group_members";

    static final String SCHEMA_DDL = """
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
}
