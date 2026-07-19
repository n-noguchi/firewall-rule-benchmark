package firewall;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an immutable {@link Engine} from any {@link MasterSource}. Mirrors the
 * priority-indexed loader: same-condition rules keep only the minimum priority
 * (others can never be First Match), exact indexes use sorted arrays, time
 * indexes switch to the dense point index at >=64 distinct ranges.
 */
final class Loader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Loader() {
    }

    static Engine load(MasterSource source) throws IOException, SQLException, InterruptedException {
        String dataVersion = source.dataVersion();
        Map<String, TenantData> tenants = new HashMap<>();
        Map<String, Policy> policiesById = new HashMap<>();
        Set<String> policyScopes = new HashSet<>();

        source.readPolicies(row -> {
            String id = row[0];
            String tenantId = row[1];
            String departmentId = row[2];
            String action = row[3];
            boolean enabled = parseBoolean(row[4]);
            if (id.isEmpty() || tenantId.isEmpty() || !actionValid(action) || row[4].isEmpty()) {
                throw new IOException("invalid or unsupported policy " + id);
            }
            if (policiesById.containsKey(id)) {
                throw new IOException("duplicate policy_id " + id);
            }
            String scope = tenantId + "\u0000" + departmentId;
            if (!policyScopes.add(scope)) {
                throw new IOException("duplicate policy scope for " + tenantId + "/" + departmentId);
            }
            Policy policy = new Policy(id, action);
            policiesById.put(id, policy);
            if (!enabled) {
                return;
            }
            TenantData tenant = tenantOf(tenants, tenantId);
            if (departmentId.isEmpty()) {
                if (tenant.policy != null) {
                    throw new IOException("duplicate tenant policy for " + tenantId);
                }
                tenant.policy = policy;
            } else {
                if (tenant.departments.containsKey(departmentId)) {
                    throw new IOException("duplicate department policy for " + tenantId + "/" + departmentId);
                }
                tenant.departments.put(departmentId, policy);
            }
        });

        source.readRules(row -> {
            String id = row[0];
            Policy policy = policiesById.get(row[1]);
            int priority;
            try {
                priority = Integer.parseInt(row[2]);
            } catch (NumberFormatException e) {
                throw new IOException("invalid or unsupported rule " + id);
            }
            boolean enabled = parseBoolean(row[5]);
            if (id.isEmpty() || policy == null || priority < 1 || row[5].isEmpty() || !actionValid(row[4])) {
                throw new IOException("invalid or unsupported rule " + id);
            }
            Policy.Builder builder = policy.builder;
            builder.priorities.add(priority);
            Candidate candidate = new Candidate(priority, id, row[4]);
            String ruleType = row[3];
            if ("SOURCE_IPV4_REGEX".equals(ruleType)) {
                addRegex(builder, Policy.SOURCE_IPV4, row[6], candidate, id);
            } else if ("URL_PATH_REGEX".equals(ruleType)) {
                addRegex(builder, Policy.URL_PATH, row[6], candidate, id);
            } else if ("REFERER_REGEX".equals(ruleType)) {
                addRegex(builder, Policy.REFERER, row[6], candidate, id);
            } else if ("USER_AGENT_REGEX".equals(ruleType)) {
                addRegex(builder, Policy.USER_AGENT, row[6], candidate, id);
            } else if ("SOURCE_IPV4_GROUP".equals(ruleType)) {
                if (row[7].isEmpty()) throw new IOException("rule " + id + ": source_ip_group_key is required");
                if (enabled) builder.groups.add(new Policy.GroupRuleRaw(row[7], candidate));
            } else if ("ACCESS_TIME_RANGE".equals(ruleType)) {
                int start = timeOfDaySeconds(row[8]);
                int end = timeOfDaySeconds(row[9]);
                if (start == end) throw new IOException("rule " + id + ": start_time_utc and end_time_utc must differ");
                if (enabled) builder.times.add(new TimeIndex.TimeRule(start, end, candidate));
            } else {
                throw new IOException("rule " + id + ": unknown rule_type " + row[3]);
            }
        });

        Map<String, GroupDefinition> groupsById = new HashMap<>();
        source.readGroups(row -> {
            String id = row[0];
            String tenantId = row[1];
            String departmentId = row[2];
            String key = row[3];
            if (id.isEmpty() || tenantId.isEmpty() || key.isEmpty()) {
                throw new IOException("invalid source IP group " + id);
            }
            if (groupsById.containsKey(id)) {
                throw new IOException("duplicate group_id " + id);
            }
            TenantData tenant = tenantOf(tenants, tenantId);
            GroupSet set;
            if (departmentId.isEmpty()) {
                if (tenant.tenantGroups == null) {
                    tenant.tenantGroups = new GroupSet();
                }
                set = tenant.tenantGroups;
            } else {
                set = tenant.departmentGroups.get(departmentId);
                if (set == null) {
                    set = new GroupSet();
                    tenant.departmentGroups.put(departmentId, set);
                }
            }
            if (!set.definedKeys.add(key)) {
                throw new IOException("duplicate group_key " + key + " in " + tenantId + "/" + departmentId);
            }
            groupsById.put(id, new GroupDefinition(key, set));
        });

        source.readMembers(row -> {
            GroupDefinition definition = groupsById.get(row[0]);
            String address = row[1];
            if (definition == null || address.isEmpty()) {
                throw new IOException("member references unknown group " + row[0]);
            }
            if (!definition.members.add(address)) {
                throw new IOException("duplicate member " + address + " in group " + row[0]);
            }
            definition.set.keysByAddress
                    .computeIfAbsent(address, k -> new ArrayList<String>())
                    .add(definition.key);
        });

        for (Policy policy : policiesById.values()) {
            finalizePolicy(policy);
        }
        return new Engine(tenants, dataVersion);
    }

    private static void addRegex(Policy.Builder builder, int field, String pattern, Candidate candidate, String ruleId) throws IOException {
        if (pattern.isEmpty()) {
            throw new IOException("rule " + ruleId + ": regex_pattern is required");
        }
        builder.regex[field].add(new Policy.RegexRuleRaw(pattern, candidate));
    }

    private static TenantData tenantOf(Map<String, TenantData> tenants, String tenantId) {
        return tenants.computeIfAbsent(tenantId, k -> new TenantData());
    }

    private static void finalizePolicy(Policy policy) {
        Policy.Builder builder = policy.builder;
        if (builder == null) {
            return;
        }
        if (builder.priorities.isEmpty()) {
            policy.minimum = Integer.MAX_VALUE;
        } else {
            List<Integer> sorted = new ArrayList<Integer>(builder.priorities);
            Collections.sort(sorted);
            policy.minimum = sorted.get(0);
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).equals(sorted.get(i - 1))) {
                    throw new RuntimeException("duplicate priority " + sorted.get(i));
                }
            }
        }
        for (int index = 0; index < builder.regex.length; index++) {
            RegexIndex.Builder regexBuilder = new RegexIndex.Builder();
            for (Policy.RegexRuleRaw raw : builder.regex[index]) {
                regexBuilder.add(raw.pattern, raw.candidate);
            }
            policy.regex[index] = regexBuilder.build();
        }
        if (!builder.groups.isEmpty()) {
            builder.groups.sort(new java.util.Comparator<Policy.GroupRuleRaw>() {
                @Override
                public int compare(Policy.GroupRuleRaw a, Policy.GroupRuleRaw b) {
                    if (!a.key.equals(b.key)) return a.key.compareTo(b.key);
                    return Integer.compare(a.candidate.priority(), b.candidate.priority());
                }
            });
            Map<String, Candidate> groupRules = new HashMap<String, Candidate>();
            for (Policy.GroupRuleRaw group : builder.groups) {
                groupRules.putIfAbsent(group.key, group.candidate);
            }
            policy.groupRules = groupRules;
        }
        TimeIndex.Builder timeBuilder = new TimeIndex.Builder();
        for (TimeIndex.TimeRule rule : builder.times) {
            timeBuilder.add(rule.start, rule.end, rule.candidate);
        }
        policy.times = timeBuilder.build();
        policy.builder = null;
    }

    private static final class GroupDefinition {
        final String key;
        final GroupSet set;
        final Set<String> members = new HashSet<String>();

        GroupDefinition(String key, GroupSet set) {
            this.key = key;
            this.set = set;
        }
    }

    static boolean actionValid(String value) {
        return "ALLOW".equals(value) || "DENY".equals(value);
    }

    static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value);
    }

    static int timeOfDaySeconds(String value) throws IOException {
        try {
            LocalTime parsed = LocalTime.parse(value);
            return parsed.toSecondOfDay();
        } catch (DateTimeParseException e) {
            throw new IOException("invalid time " + value, e);
        }
    }

    static String readDatasetVersion(Path dataDirectory) throws IOException {
        byte[] manifest = Files.readAllBytes(dataDirectory.resolve("manifest.json"));
        String version = MAPPER.readTree(manifest).path("dataset_version").asText("");
        if (version.isEmpty()) {
            throw new IOException("invalid dataset manifest");
        }
        return version;
    }
}
