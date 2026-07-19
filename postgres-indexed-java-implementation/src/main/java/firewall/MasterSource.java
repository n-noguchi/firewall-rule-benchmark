package firewall;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Source of master data. The same interface is implemented by the CSV import
 * directory and the PostgreSQL-backed store so the engine loader can stay
 * storage-agnostic.
 */
public interface MasterSource {
    String dataVersion() throws IOException, SQLException, InterruptedException;

    /**
     * Streams policies as raw rows in the canonical CSV column order:
     * policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at.
     */
    void readPolicies(RowConsumer consumer) throws IOException, SQLException, InterruptedException;

    /**
     * Streams firewall rules in canonical CSV column order:
     * rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern,
     * source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at.
     */
    void readRules(RowConsumer consumer) throws IOException, SQLException, InterruptedException;

    /** Streams source IP groups: group_id, tenant_id, department_id, group_key, name, created_at, updated_at. */
    void readGroups(RowConsumer consumer) throws IOException, SQLException, InterruptedException;

    /** Streams group members: group_id, source_ipv4, created_at, updated_at. */
    void readMembers(RowConsumer consumer) throws IOException, SQLException, InterruptedException;

    @FunctionalInterface
    interface RowConsumer {
        void accept(String[] row) throws IOException, SQLException, InterruptedException;
    }
}
