package firewall;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Admin CRUD handler backed by {@link AdminService}. Routes four entity kinds. */
final class AdminHandler {
    private static final Pattern POLICY_PATH = Pattern.compile("^/v1/admin/policies/([^/]+)$");
    private static final Pattern RULE_PATH = Pattern.compile("^/v1/admin/rules/([^/]+)$");
    private static final Pattern GROUP_PATH = Pattern.compile("^/v1/admin/source-ip-groups/([^/]+)$");
    private static final Pattern MEMBER_PATH = Pattern.compile("^/v1/admin/source-ip-groups/([^/]+)/members/([^/]+)$");

    private final AdminService service;

    AdminHandler(AdminService service) {
        this.service = service;
    }

    HttpHandler handler() {
        return exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                try {
                    route(exchange, path, method);
                } catch (MasterStore.MasterNotFoundException nf) {
                    writeJson(exchange, 404, Json.error(nf.getMessage() == null ? "master record not found" : nf.getMessage()));
                } catch (IllegalArgumentException bad) {
                    writeJson(exchange, 400, Json.error(bad.getMessage()));
                } catch (Exception e) {
                    writeJson(exchange, 409, Json.error(e.getMessage()));
                }
            }
        };
    }

    private void route(HttpExchange exchange, String path, String method) throws Exception {
        Matcher member = MEMBER_PATH.matcher(path);
        if (member.matches()) {
            String groupId = decode(member.group(1));
            String ipv4 = decode(member.group(2));
            switch (method) {
                case "GET" -> writeJson(exchange, 200, service.getMember(groupId, ipv4));
                case "PUT" -> {
                    MemberRecord record = parseBody(exchange, MemberRecord.class);
                    service.putMember(groupId, ipv4, record);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                case "DELETE" -> {
                    service.deleteMember(groupId, ipv4);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                default -> writeJson(exchange, 405, Json.error("method not allowed"));
            }
            return;
        }
        Matcher policy = POLICY_PATH.matcher(path);
        if (policy.matches()) {
            String id = decode(policy.group(1));
            switch (method) {
                case "GET" -> writeJson(exchange, 200, service.getPolicy(id));
                case "PUT" -> {
                    PolicyRecord record = parseBody(exchange, PolicyRecord.class);
                    service.putPolicy(id, record);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                case "DELETE" -> {
                    service.deletePolicy(id);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                default -> writeJson(exchange, 405, Json.error("method not allowed"));
            }
            return;
        }
        Matcher rule = RULE_PATH.matcher(path);
        if (rule.matches()) {
            String id = decode(rule.group(1));
            switch (method) {
                case "GET" -> writeJson(exchange, 200, service.getRule(id));
                case "PUT" -> {
                    RuleRecord record = parseBody(exchange, RuleRecord.class);
                    service.putRule(id, record);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                case "DELETE" -> {
                    service.deleteRule(id);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                default -> writeJson(exchange, 405, Json.error("method not allowed"));
            }
            return;
        }
        Matcher group = GROUP_PATH.matcher(path);
        if (group.matches()) {
            String id = decode(group.group(1));
            switch (method) {
                case "GET" -> writeJson(exchange, 200, service.getGroup(id));
                case "PUT" -> {
                    GroupRecord record = parseBody(exchange, GroupRecord.class);
                    service.putGroup(id, record);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                case "DELETE" -> {
                    service.deleteGroup(id);
                    writeJson(exchange, 200, Json.status("applied"));
                }
                default -> writeJson(exchange, 405, Json.error("method not allowed"));
            }
            return;
        }
        writeJson(exchange, 404, Json.error("unknown admin path: " + path));
    }

    private static <T> T parseBody(HttpExchange exchange, Class<T> type) throws IOException {
        return Json.MAPPER.readValue(exchange.getRequestBody(), type);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        ServerHandler.writeJson(exchange, status, body);
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class PolicyRecord {
        @JsonProperty("policy_id") public String policyId;
        @JsonProperty("tenant_id") public String tenantId;
        @JsonProperty("department_id") public String departmentId;
        @JsonProperty("default_action") public String defaultAction;
        @JsonProperty("enabled") public boolean enabled;
        @JsonProperty("created_at") public String createdAt;
        @JsonProperty("updated_at") public String updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class RuleRecord {
        @JsonProperty("rule_id") public String ruleId;
        @JsonProperty("policy_id") public String policyId;
        @JsonProperty("priority") public int priority;
        @JsonProperty("rule_type") public String ruleType;
        @JsonProperty("action") public String action;
        @JsonProperty("enabled") public boolean enabled;
        @JsonProperty("regex_pattern") public String regexPattern;
        @JsonProperty("source_ip_group_key") public String sourceIpGroupKey;
        @JsonProperty("start_time_utc") public String startTimeUtc;
        @JsonProperty("end_time_utc") public String endTimeUtc;
        @JsonProperty("created_at") public String createdAt;
        @JsonProperty("updated_at") public String updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class GroupRecord {
        @JsonProperty("group_id") public String groupId;
        @JsonProperty("tenant_id") public String tenantId;
        @JsonProperty("department_id") public String departmentId;
        @JsonProperty("group_key") public String groupKey;
        @JsonProperty("name") public String name;
        @JsonProperty("created_at") public String createdAt;
        @JsonProperty("updated_at") public String updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static final class MemberRecord {
        @JsonProperty("group_id") public String groupId;
        @JsonProperty("source_ipv4") public String sourceIpv4;
        @JsonProperty("created_at") public String createdAt;
        @JsonProperty("updated_at") public String updatedAt;
    }

    static String nowIso() {
        return Instant.now().toString();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> linkedMap() {
        return new LinkedHashMap<>();
    }
}
