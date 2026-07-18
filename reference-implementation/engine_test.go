package main

import (
	"regexp"
	"testing"
)

func makeRule(id string, priority int, kind, action, pattern, groupKey string, start, end int) *Rule {
	rule := &Rule{ID: id, Priority: priority, Type: kind, Action: action, Pattern: pattern, GroupKey: groupKey, Start: start, End: end}
	if pattern != "" {
		rule.Regex = regexp.MustCompile(pattern)
	}
	return rule
}

func makeEngine(policy *Policy) *Engine {
	return &Engine{
		tenantPolicies: map[string]*Policy{"tenant-a": policy},
		deptPolicies:   make(map[string]*Policy),
		groupsByScope:  make(map[string]map[string]*group),
	}
}

func baseAccess() AccessLog {
	return AccessLog{AccessID: "access-1", SourceIPv4: "192.0.2.10", URLPath: "/tenant-a/dept-a/api/users/1", AccessTimestampUTC: "2026-07-18T12:00:00Z", Referer: "", UserAgent: "Mozilla/5.0"}
}

func TestEveryRuleTypeAndRegexPartialMatch(t *testing.T) {
	tests := []struct {
		name   string
		rule   *Rule
		access AccessLog
	}{
		{"source IP regex", makeRule("r", 1, "SOURCE_IPV4_REGEX", "DENY", `^192\.0\.2\.10$`, "", 0, 0), baseAccess()},
		{"URL path regex", makeRule("r", 1, "URL_PATH_REGEX", "DENY", `/api/users/`, "", 0, 0), baseAccess()},
		{"referer regex", makeRule("r", 1, "REFERER_REGEX", "DENY", `trusted`, "", 0, 0), func() AccessLog { a := baseAccess(); a.Referer = "https://trusted.example/"; return a }()},
		{"user agent regex", makeRule("r", 1, "USER_AGENT_REGEX", "DENY", `Chrome`, "", 0, 0), func() AccessLog { a := baseAccess(); a.UserAgent = "Mozilla Chrome/150"; return a }()},
		{"ordinary time range start inclusive", makeRule("r", 1, "ACCESS_TIME_RANGE", "DENY", "", "", 9*3600, 18*3600), func() AccessLog { a := baseAccess(); a.AccessTimestampUTC = "2026-07-18T09:00:00Z"; return a }()},
		{"overnight time range", makeRule("r", 1, "ACCESS_TIME_RANGE", "DENY", "", "", 22*3600, 6*3600), func() AccessLog { a := baseAccess(); a.AccessTimestampUTC = "2026-07-18T02:00:00Z"; return a }()},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			engine := makeEngine(&Policy{ID: "tenant-policy", TenantID: "tenant-a", DefaultAction: "ALLOW", Rules: []*Rule{test.rule}})
			got, err := engine.Evaluate(test.access)
			if err != nil || got.MatchedRuleID != "r" || got.Action != "DENY" {
				t.Fatalf("got=%+v err=%v", got, err)
			}
		})
	}
}

func TestDepartmentPolicyAndGroupSetDoNotFallBackIndividually(t *testing.T) {
	tenantPolicy := &Policy{ID: "tenant-policy", TenantID: "tenant-a", DefaultAction: "ALLOW", Rules: []*Rule{makeRule("tenant-rule", 1, "SOURCE_IPV4_GROUP", "ALLOW", "", "office", 0, 0)}}
	departmentPolicy := &Policy{ID: "department-policy", TenantID: "tenant-a", DepartmentID: "dept-a", DefaultAction: "DENY", Rules: []*Rule{makeRule("department-rule", 1, "SOURCE_IPV4_GROUP", "ALLOW", "", "office", 0, 0)}}
	engine := makeEngine(tenantPolicy)
	engine.deptPolicies[scopeKey("tenant-a", "dept-a")] = departmentPolicy
	engine.groupsByScope[scopeKey("tenant-a", "")] = map[string]*group{
		"office": {ID: "tenant-office", MemberSet: map[string]struct{}{"192.0.2.10": {}}},
	}
	// The department has a group set, but it deliberately lacks "office". The tenant office group must not be used.
	engine.groupsByScope[scopeKey("tenant-a", "dept-a")] = map[string]*group{
		"vip": {ID: "department-vip", MemberSet: map[string]struct{}{}},
	}
	got, err := engine.Evaluate(baseAccess())
	if err != nil {
		t.Fatal(err)
	}
	if got.SelectedPolicyID != "department-policy" || got.MatchedRuleID != "" || got.Action != "DENY" {
		t.Fatalf("department policy/group selection incorrect: %+v", got)
	}
}

func TestFirstMatchAndTimeEndExclusive(t *testing.T) {
	policy := &Policy{ID: "policy", TenantID: "tenant-a", DefaultAction: "ALLOW", Rules: []*Rule{
		makeRule("first", 1, "USER_AGENT_REGEX", "DENY", `Mozilla`, "", 0, 0),
		makeRule("later", 2, "SOURCE_IPV4_REGEX", "ALLOW", `.*`, "", 0, 0),
	}}
	got, err := makeEngine(policy).Evaluate(baseAccess())
	if err != nil || got.MatchedRuleID != "first" || got.Action != "DENY" {
		t.Fatalf("first match failed: %+v err=%v", got, err)
	}
	policy.Rules = []*Rule{makeRule("time", 1, "ACCESS_TIME_RANGE", "DENY", "", "", 9*3600, 18*3600)}
	access := baseAccess()
	access.AccessTimestampUTC = "2026-07-18T18:00:00Z"
	got, err = makeEngine(policy).Evaluate(access)
	if err != nil || got.MatchedRuleID != "" || got.Action != "ALLOW" {
		t.Fatalf("half-open end boundary failed: %+v err=%v", got, err)
	}
}
