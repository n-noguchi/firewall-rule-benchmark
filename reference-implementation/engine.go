package main

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"regexp/syntax"
	"sort"
	"strconv"
	"strings"
	"time"
)

var (
	policyHeader = []string{"policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"}
	ruleHeader   = []string{"rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern", "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at"}
	groupHeader  = []string{"group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"}
	memberHeader = []string{"group_id", "source_ipv4", "created_at", "updated_at"}
)

func LoadEngine(dataDir string) (*Engine, error) {
	manifest, err := os.ReadFile(filepathJoin(dataDir, "manifest.json"))
	if err != nil {
		return nil, fmt.Errorf("read dataset manifest: %w", err)
	}
	var metadata struct {
		DatasetVersion string `json:"dataset_version"`
	}
	if err := json.Unmarshal(manifest, &metadata); err != nil || metadata.DatasetVersion == "" {
		return nil, fmt.Errorf("invalid dataset manifest")
	}
	engine := &Engine{
		tenantPolicies: make(map[string]*Policy),
		deptPolicies:   make(map[string]*Policy),
		groupsByScope:  make(map[string]map[string]*group),
		DataVersion:    metadata.DatasetVersion,
	}
	policiesByID := make(map[string]*Policy)
	if err := readCSVDirectory(filepathJoin(dataDir, "policies"), policyHeader, func(row map[string]string) error {
		id, tenant := row["policy_id"], row["tenant_id"]
		department := row["department_id"]
		if id == "" || tenant == "" || !actionValid(row["default_action"]) {
			return fmt.Errorf("invalid policy %q", id)
		}
		if row["enabled"] != "true" {
			return fmt.Errorf("policy %q has unsupported enabled=%q", id, row["enabled"])
		}
		if _, exists := policiesByID[id]; exists {
			return fmt.Errorf("duplicate policy_id %q", id)
		}
		policy := &Policy{ID: id, TenantID: tenant, DepartmentID: department, DefaultAction: row["default_action"]}
		policiesByID[id] = policy
		if department == "" {
			if _, exists := engine.tenantPolicies[tenant]; exists {
				return fmt.Errorf("duplicate tenant policy for %q", tenant)
			}
			engine.tenantPolicies[tenant] = policy
		} else {
			key := scopeKey(tenant, department)
			if _, exists := engine.deptPolicies[key]; exists {
				return fmt.Errorf("duplicate department policy for %s/%s", tenant, department)
			}
			engine.deptPolicies[key] = policy
		}
		return nil
	}); err != nil {
		return nil, err
	}
	if err := readCSVDirectory(filepathJoin(dataDir, "firewall_rules"), ruleHeader, func(row map[string]string) error {
		policy := policiesByID[row["policy_id"]]
		if policy == nil {
			return fmt.Errorf("rule %q references unknown policy %q", row["rule_id"], row["policy_id"])
		}
		if row["rule_id"] == "" || row["enabled"] != "true" || !actionValid(row["action"]) {
			return fmt.Errorf("invalid or unsupported rule %q", row["rule_id"])
		}
		priority, err := strconv.Atoi(row["priority"])
		if err != nil || priority < 1 {
			return fmt.Errorf("rule %q has invalid priority %q", row["rule_id"], row["priority"])
		}
		rule, err := newRule(row, priority)
		if err != nil {
			return fmt.Errorf("rule %q: %w", row["rule_id"], err)
		}
		policy.Rules = append(policy.Rules, rule)
		return nil
	}); err != nil {
		return nil, err
	}

	groupsByID := make(map[string]*group)
	if err := readCSVDirectory(filepathJoin(dataDir, "source_ip_groups"), groupHeader, func(row map[string]string) error {
		id, tenant, key := row["group_id"], row["tenant_id"], row["group_key"]
		if id == "" || tenant == "" || key == "" {
			return fmt.Errorf("invalid source IP group %q", id)
		}
		if _, exists := groupsByID[id]; exists {
			return fmt.Errorf("duplicate group_id %q", id)
		}
		scope := scopeKey(tenant, row["department_id"])
		set := engine.groupsByScope[scope]
		if set == nil {
			set = make(map[string]*group)
			engine.groupsByScope[scope] = set
		}
		if _, exists := set[key]; exists {
			return fmt.Errorf("duplicate group_key %q in scope %q", key, scope)
		}
		value := &group{ID: id, TenantID: tenant, Department: row["department_id"], Key: key, MemberSet: make(map[string]struct{})}
		set[key] = value
		groupsByID[id] = value
		return nil
	}); err != nil {
		return nil, err
	}
	if err := readCSVDirectory(filepathJoin(dataDir, "source_ip_group_members"), memberHeader, func(row map[string]string) error {
		value := groupsByID[row["group_id"]]
		if value == nil || row["source_ipv4"] == "" {
			return fmt.Errorf("member references unknown group %q", row["group_id"])
		}
		if _, exists := value.MemberSet[row["source_ipv4"]]; exists {
			return fmt.Errorf("duplicate member %q in group %q", row["source_ipv4"], value.ID)
		}
		value.MemberSet[row["source_ipv4"]] = struct{}{}
		return nil
	}); err != nil {
		return nil, err
	}
	for _, policy := range policiesByID {
		sort.Slice(policy.Rules, func(i, j int) bool { return policy.Rules[i].Priority < policy.Rules[j].Priority })
		for index := 1; index < len(policy.Rules); index++ {
			if policy.Rules[index-1].Priority == policy.Rules[index].Priority {
				return nil, fmt.Errorf("duplicate rule priority %d in policy %q", policy.Rules[index].Priority, policy.ID)
			}
		}
	}
	return engine, nil
}

func filepathJoin(first, second string) string {
	// Kept local so data loading has no dependency on the generator package.
	return strings.TrimRight(first, "/\\") + "/" + second
}

func newRule(row map[string]string, priority int) (*Rule, error) {
	rule := &Rule{ID: row["rule_id"], Priority: priority, Type: row["rule_type"], Action: row["action"], Pattern: row["regex_pattern"], GroupKey: row["source_ip_group_key"]}
	switch rule.Type {
	case "SOURCE_IPV4_REGEX", "URL_PATH_REGEX", "REFERER_REGEX", "USER_AGENT_REGEX":
		if rule.Pattern == "" {
			return nil, errorsNew("regex_pattern is required")
		}
		_, err := syntax.Parse(rule.Pattern, syntax.Perl)
		if err != nil {
			return nil, fmt.Errorf("invalid RE2 pattern: %w", err)
		}
		rule.Regex = lazyRegexp(rule.Pattern)
	case "SOURCE_IPV4_GROUP":
		if rule.GroupKey == "" {
			return nil, errorsNew("source_ip_group_key is required")
		}
	case "ACCESS_TIME_RANGE":
		start, err := timeOfDaySeconds(row["start_time_utc"])
		if err != nil {
			return nil, fmt.Errorf("invalid start_time_utc: %w", err)
		}
		end, err := timeOfDaySeconds(row["end_time_utc"])
		if err != nil {
			return nil, fmt.Errorf("invalid end_time_utc: %w", err)
		}
		if start == end {
			return nil, errorsNew("start_time_utc and end_time_utc must differ")
		}
		rule.Start, rule.End = start, end
	default:
		return nil, fmt.Errorf("unknown rule_type %q", rule.Type)
	}
	return rule, nil
}

type lazyRegexp string

func (pattern lazyRegexp) MatchString(input string) bool {
	matched, _ := regexp.MatchString(string(pattern), input)
	return matched
}

func errorsNew(text string) error { return fmt.Errorf("%s", text) }

func timeOfDaySeconds(value string) (int, error) {
	parsed, err := time.Parse("15:04:05", value)
	if err != nil {
		return 0, err
	}
	return parsed.Hour()*3600 + parsed.Minute()*60 + parsed.Second(), nil
}

func parseURLScope(path string) (string, string, error) {
	parts := strings.Split(path, "/")
	if len(parts) < 4 || parts[0] != "" || parts[1] == "" || parts[2] == "" {
		return "", "", fmt.Errorf("URL path must begin /{tenant_id}/{department_id}/: %q", path)
	}
	return parts[1], parts[2], nil
}

func (engine *Engine) Evaluate(access AccessLog) (Result, error) {
	if access.AccessID == "" || access.SourceIPv4 == "" || access.URLPath == "" || access.AccessTimestampUTC == "" {
		return Result{}, errorsNew("access_id, source_ipv4, url_path, and access_timestamp_utc are required")
	}
	tenant, department, err := parseURLScope(access.URLPath)
	if err != nil {
		return Result{}, err
	}
	policy := engine.deptPolicies[scopeKey(tenant, department)]
	if policy == nil {
		policy = engine.tenantPolicies[tenant]
	}
	if policy == nil {
		return Result{}, fmt.Errorf("no policy exists for tenant %q", tenant)
	}
	accessTime, err := time.Parse(time.RFC3339Nano, access.AccessTimestampUTC)
	if err != nil {
		return Result{}, fmt.Errorf("invalid access_timestamp_utc: %w", err)
	}
	accessSeconds := accessTime.UTC().Hour()*3600 + accessTime.UTC().Minute()*60 + accessTime.UTC().Second()
	for _, rule := range policy.Rules {
		matches := false
		switch rule.Type {
		case "SOURCE_IPV4_REGEX":
			matches = rule.Regex.MatchString(access.SourceIPv4)
		case "URL_PATH_REGEX":
			matches = rule.Regex.MatchString(access.URLPath)
		case "REFERER_REGEX":
			matches = rule.Regex.MatchString(access.Referer)
		case "USER_AGENT_REGEX":
			matches = rule.Regex.MatchString(access.UserAgent)
		case "SOURCE_IPV4_GROUP":
			set := engine.groupsByScope[scopeKey(tenant, department)]
			if set == nil {
				set = engine.groupsByScope[scopeKey(tenant, "")]
			}
			if value := set[rule.GroupKey]; value != nil {
				_, matches = value.MemberSet[access.SourceIPv4]
			}
		case "ACCESS_TIME_RANGE":
			if rule.Start < rule.End {
				matches = rule.Start <= accessSeconds && accessSeconds < rule.End
			} else {
				matches = rule.Start <= accessSeconds || accessSeconds < rule.End
			}
		}
		if matches {
			return Result{AccessID: access.AccessID, SelectedPolicyID: policy.ID, MatchedRuleID: rule.ID, Action: rule.Action}, nil
		}
	}
	return Result{AccessID: access.AccessID, SelectedPolicyID: policy.ID, Action: policy.DefaultAction}, nil
}
