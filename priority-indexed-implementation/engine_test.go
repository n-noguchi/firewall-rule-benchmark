package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func testPolicy(t *testing.T, id, defaultAction string, rules func(*policyBuilder)) *Policy {
	t.Helper()
	policy := newPolicy(id, defaultAction)
	rules(policy.builder)
	if err := finalizePolicy(policy); err != nil {
		t.Fatal(err)
	}
	return policy
}

func testAccess() AccessLog {
	return AccessLog{
		AccessID: "access-1", SourceIPv4: "192.0.2.10", URLPath: "/tenant-a/dept-a/api/users/1",
		AccessTimestampUTC: "2026-07-18T12:00:00Z", UserAgent: "Mozilla Chrome/150",
	}
}

func TestEvaluateUsesLowestMatchingPriorityAcrossIndexes(t *testing.T) {
	policy := testPolicy(t, "policy-a", "ALLOW", func(builder *policyBuilder) {
		builder.regex[0] = append(builder.regex[0], rawRegexRule{pattern: `^192\.0\.2\.10$`, candidate: candidate{priority: 20, ruleID: "source", action: "ALLOW"}})
		builder.regex[3] = append(builder.regex[3], rawRegexRule{pattern: `Chrome`, candidate: candidate{priority: 10, ruleID: "agent", action: "DENY"}})
		builder.priorities = []int32{20, 10}
	})
	engine := &Engine{tenants: map[string]*tenantData{"tenant-a": {policy: policy, departments: map[string]*Policy{}, departmentGroups: map[string]*groupSet{}}}}
	got, err := engine.Evaluate(testAccess())
	if err != nil || got.MatchedRuleID != "agent" || got.Action != "DENY" {
		t.Fatalf("got=%+v err=%v", got, err)
	}
}

func TestDepartmentPolicyAndGroupSetNeverFallBackIndividually(t *testing.T) {
	tenantPolicy := testPolicy(t, "tenant-policy", "ALLOW", func(builder *policyBuilder) {
		builder.groups = []rawGroupRule{{key: "office", candidate: candidate{priority: 1, ruleID: "tenant-rule", action: "ALLOW"}}}
		builder.priorities = []int32{1}
	})
	departmentPolicy := testPolicy(t, "department-policy", "DENY", func(builder *policyBuilder) {
		builder.groups = []rawGroupRule{{key: "office", candidate: candidate{priority: 1, ruleID: "department-rule", action: "ALLOW"}}}
		builder.priorities = []int32{1}
	})
	tenantGroups := newGroupSet()
	tenantGroups.definedKeys["office"] = struct{}{}
	tenantGroups.keysByAddress["192.0.2.10"] = []string{"office"}
	departmentGroups := newGroupSet()
	departmentGroups.definedKeys["vip"] = struct{}{}
	tenant := &tenantData{
		policy: tenantPolicy, departments: map[string]*Policy{"dept-a": departmentPolicy}, tenantGroups: tenantGroups,
		departmentGroups: map[string]*groupSet{"dept-a": departmentGroups},
	}
	engine := &Engine{tenants: map[string]*tenantData{"tenant-a": tenant}}
	got, err := engine.Evaluate(testAccess())
	if err != nil || got.SelectedPolicyID != "department-policy" || got.MatchedRuleID != "" || got.Action != "DENY" {
		t.Fatalf("got=%+v err=%v", got, err)
	}
}

func TestDevelopmentDatasetMatchesExpectedResults(t *testing.T) {
	data := filepath.Join("..", "benchmark-data", "development-v1")
	engine, err := LoadEngine(data)
	if err != nil {
		t.Fatal(err)
	}
	accesses, err := ReadAccessLogs(filepath.Join(data, "access_logs"))
	if err != nil {
		t.Fatal(err)
	}
	actual, err := engine.EvaluateMany(accesses, 4)
	if err != nil {
		t.Fatal(err)
	}
	expected, err := ReadExpected(filepath.Join(data, "expected_results"))
	if err != nil {
		t.Fatal(err)
	}
	if len(actual) != len(expected) {
		t.Fatalf("actual=%d expected=%d", len(actual), len(expected))
	}
	for _, got := range actual {
		if want := expected[got.AccessID]; got != want {
			t.Fatalf("%s: got=%+v want=%+v", got.AccessID, got, want)
		}
	}
}

func TestHTTPAPI(t *testing.T) {
	data := filepath.Join("..", "benchmark-data", "development-v1")
	engine, err := LoadEngine(data)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(newHandler(engine))
	defer server.Close()
	response, err := http.Get(server.URL + "/health/ready")
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("ready status %d", response.StatusCode)
	}
	response.Body.Close()
	accesses, err := ReadAccessLogs(filepath.Join(data, "access_logs"))
	if err != nil {
		t.Fatal(err)
	}
	payload, _ := json.Marshal(accesses[0])
	response, err = http.Post(server.URL+"/v1/firewall/evaluate", "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("evaluate status %d", response.StatusCode)
	}
	var got evaluationResponse
	if err := json.NewDecoder(response.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	want, err := engine.Evaluate(accesses[0])
	if err != nil {
		t.Fatal(err)
	}
	matched := ""
	if got.MatchedRuleID != nil {
		matched = *got.MatchedRuleID
	}
	if got.AccessID != want.AccessID || got.SelectedPolicyID != want.SelectedPolicyID || got.Action != want.Action || matched != want.MatchedRuleID {
		t.Fatalf("got=%+v want=%+v", got, want)
	}
}
