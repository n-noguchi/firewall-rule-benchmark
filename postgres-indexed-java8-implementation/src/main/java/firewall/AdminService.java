package firewall;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Service layer that issues master CRUD operations against the PostgreSQL store
 * and atomically swaps the live engine after every successful mutation.
 */
final class AdminService {
    private final MasterStore store;
    private final EngineHolder holder;

    AdminService(MasterStore store, EngineHolder holder) {
        this.store = store;
        this.holder = holder;
    }

    AdminHandler.PolicyRecord getPolicy(String id) throws SQLException {
        try (Connection c = store.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT policy_id, tenant_id, department_id, default_action, enabled, created_at, updated_at FROM policies WHERE policy_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new MasterStore.MasterNotFoundException();
                AdminHandler.PolicyRecord record = new AdminHandler.PolicyRecord();
                record.policyId = rs.getString(1);
                record.tenantId = rs.getString(2);
                record.departmentId = rs.getString(3);
                record.defaultAction = rs.getString(4);
                record.enabled = rs.getBoolean(5);
                record.createdAt = rs.getString(6);
                record.updatedAt = rs.getString(7);
                return record;
            }
        }
    }

    AdminHandler.RuleRecord getRule(String id) throws SQLException {
        try (Connection c = store.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT rule_id, policy_id, priority, rule_type, action, enabled, regex_pattern, source_ip_group_key, start_time_utc, end_time_utc, created_at, updated_at FROM firewall_rules WHERE rule_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new MasterStore.MasterNotFoundException();
                AdminHandler.RuleRecord record = new AdminHandler.RuleRecord();
                record.ruleId = rs.getString(1);
                record.policyId = rs.getString(2);
                record.priority = rs.getInt(3);
                record.ruleType = rs.getString(4);
                record.action = rs.getString(5);
                record.enabled = rs.getBoolean(6);
                record.regexPattern = rs.getString(7);
                record.sourceIpGroupKey = rs.getString(8);
                record.startTimeUtc = rs.getString(9);
                record.endTimeUtc = rs.getString(10);
                record.createdAt = rs.getString(11);
                record.updatedAt = rs.getString(12);
                return record;
            }
        }
    }

    AdminHandler.GroupRecord getGroup(String id) throws SQLException {
        try (Connection c = store.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT group_id, tenant_id, department_id, group_key, name, created_at, updated_at FROM source_ip_groups WHERE group_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new MasterStore.MasterNotFoundException();
                AdminHandler.GroupRecord record = new AdminHandler.GroupRecord();
                record.groupId = rs.getString(1);
                record.tenantId = rs.getString(2);
                record.departmentId = rs.getString(3);
                record.groupKey = rs.getString(4);
                record.name = rs.getString(5);
                record.createdAt = rs.getString(6);
                record.updatedAt = rs.getString(7);
                return record;
            }
        }
    }

    AdminHandler.MemberRecord getMember(String groupId, String ipv4) throws SQLException {
        try (Connection c = store.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT group_id, source_ipv4, created_at, updated_at FROM source_ip_group_members WHERE group_id = ? AND source_ipv4 = ?")) {
            ps.setString(1, groupId);
            ps.setString(2, ipv4);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new MasterStore.MasterNotFoundException();
                AdminHandler.MemberRecord record = new AdminHandler.MemberRecord();
                record.groupId = rs.getString(1);
                record.sourceIpv4 = rs.getString(2);
                record.createdAt = rs.getString(3);
                record.updatedAt = rs.getString(4);
                return record;
            }
        }
    }

    void putPolicy(String id, AdminHandler.PolicyRecord record) throws Exception {
        if (id.isEmpty() || (record.policyId != null && !record.policyId.isEmpty() && !record.policyId.equals(id))) {
            throw new IllegalArgumentException("policy_id must match the URL");
        }
        if (record.createdAt == null || record.createdAt.isEmpty()) {
            try {
                record.createdAt = getPolicy(id).createdAt;
            } catch (MasterStore.MasterNotFoundException ignored) {
                record.createdAt = "";
            }
        }
        String[] times = resolveTimes(record.createdAt);
        record.policyId = id;
        record.createdAt = times[0];
        record.updatedAt = times[1];
        apply(new MasterStore.MutationSpec(
                MasterStore.POLICIES_TABLE,
                new String[]{"policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"},
                new Object[]{record.policyId, record.tenantId, record.departmentId, record.defaultAction, record.enabled, record.createdAt, record.updatedAt},
                new String[]{"policy_id"},
                new Object[]{id},
                false));
    }

    void putRule(String id, AdminHandler.RuleRecord record) throws Exception {
        if (id.isEmpty() || (record.ruleId != null && !record.ruleId.isEmpty() && !record.ruleId.equals(id))) {
            throw new IllegalArgumentException("rule_id must match the URL");
        }
        if (record.createdAt == null || record.createdAt.isEmpty()) {
            try {
                record.createdAt = getRule(id).createdAt;
            } catch (MasterStore.MasterNotFoundException ignored) {
                record.createdAt = "";
            }
        }
        String[] times = resolveTimes(record.createdAt);
        record.ruleId = id;
        record.createdAt = times[0];
        record.updatedAt = times[1];
        apply(new MasterStore.MutationSpec(
                MasterStore.RULES_TABLE,
                new String[]{"rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern", "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at"},
                new Object[]{record.ruleId, record.policyId, record.priority, record.ruleType, record.action, record.enabled,
                        record.regexPattern == null ? "" : record.regexPattern,
                        record.sourceIpGroupKey == null ? "" : record.sourceIpGroupKey,
                        record.startTimeUtc == null ? "" : record.startTimeUtc,
                        record.endTimeUtc == null ? "" : record.endTimeUtc,
                        record.createdAt, record.updatedAt},
                new String[]{"rule_id"},
                new Object[]{id},
                false));
    }

    void putGroup(String id, AdminHandler.GroupRecord record) throws Exception {
        if (id.isEmpty() || (record.groupId != null && !record.groupId.isEmpty() && !record.groupId.equals(id))) {
            throw new IllegalArgumentException("group_id must match the URL");
        }
        if (record.createdAt == null || record.createdAt.isEmpty()) {
            try {
                record.createdAt = getGroup(id).createdAt;
            } catch (MasterStore.MasterNotFoundException ignored) {
                record.createdAt = "";
            }
        }
        String[] times = resolveTimes(record.createdAt);
        record.groupId = id;
        record.createdAt = times[0];
        record.updatedAt = times[1];
        apply(new MasterStore.MutationSpec(
                MasterStore.GROUPS_TABLE,
                new String[]{"group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"},
                new Object[]{record.groupId, record.tenantId, record.departmentId, record.groupKey,
                        record.name == null ? "" : record.name, record.createdAt, record.updatedAt},
                new String[]{"group_id"},
                new Object[]{id},
                false));
    }

    void putMember(String groupId, String ipv4, AdminHandler.MemberRecord record) throws Exception {
        if (groupId.isEmpty() || ipv4.isEmpty()
                || (record.groupId != null && !record.groupId.isEmpty() && !record.groupId.equals(groupId))
                || (record.sourceIpv4 != null && !record.sourceIpv4.isEmpty() && !record.sourceIpv4.equals(ipv4))) {
            throw new IllegalArgumentException("group_id and source_ipv4 must match the URL");
        }
        if (record.createdAt == null || record.createdAt.isEmpty()) {
            try {
                record.createdAt = getMember(groupId, ipv4).createdAt;
            } catch (MasterStore.MasterNotFoundException ignored) {
                record.createdAt = "";
            }
        }
        String[] times = resolveTimes(record.createdAt);
        record.groupId = groupId;
        record.sourceIpv4 = ipv4;
        record.createdAt = times[0];
        record.updatedAt = times[1];
        apply(new MasterStore.MutationSpec(
                MasterStore.MEMBERS_TABLE,
                new String[]{"group_id", "source_ipv4", "created_at", "updated_at"},
                new Object[]{record.groupId, record.sourceIpv4, record.createdAt, record.updatedAt},
                new String[]{"group_id", "source_ipv4"},
                new Object[]{groupId, ipv4},
                false));
    }

    void deletePolicy(String id) throws Exception {
        apply(new MasterStore.MutationSpec(MasterStore.POLICIES_TABLE, new String[0], new Object[0], new String[]{"policy_id"}, new Object[]{id}, true));
    }

    void deleteRule(String id) throws Exception {
        apply(new MasterStore.MutationSpec(MasterStore.RULES_TABLE, new String[0], new Object[0], new String[]{"rule_id"}, new Object[]{id}, true));
    }

    void deleteGroup(String id) throws Exception {
        apply(new MasterStore.MutationSpec(MasterStore.GROUPS_TABLE, new String[0], new Object[0], new String[]{"group_id"}, new Object[]{id}, true));
    }

    void deleteMember(String groupId, String ipv4) throws Exception {
        apply(new MasterStore.MutationSpec(MasterStore.MEMBERS_TABLE, new String[0], new Object[0], new String[]{"group_id", "source_ipv4"}, new Object[]{groupId, ipv4}, true));
    }

    private void apply(MasterStore.MutationSpec spec) throws Exception {
        Engine next = store.runMutation(spec);
        holder.set(next);
    }

    private static String[] resolveTimes(String created) {
        String now = Instant.now().toString();
        if (created == null || created.isEmpty()) {
            return new String[]{now, now};
        }
        return new String[]{created, now};
    }
}
