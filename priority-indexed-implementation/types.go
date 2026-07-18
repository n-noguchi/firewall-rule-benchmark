package main

import "fmt"

const resultHeader = "access_id,selected_policy_id,matched_rule_id,action"

type AccessLog struct {
	AccessID           string `json:"access_id" parquet:"access_id"`
	SourceIPv4         string `json:"source_ipv4" parquet:"source_ipv4"`
	URLPath            string `json:"url_path" parquet:"url_path"`
	AccessTimestampUTC string `json:"access_timestamp_utc" parquet:"access_timestamp_utc"`
	Referer            string `json:"referer" parquet:"referer"`
	UserAgent          string `json:"user_agent" parquet:"user_agent"`
}

type Result struct {
	AccessID         string
	SelectedPolicyID string
	MatchedRuleID    string
	Action           string
}

type candidate struct {
	priority int
	ruleID   string
	action   string
}

func (value candidate) valid() bool { return value.priority > 0 }

func earlier(left, right candidate) candidate {
	if !left.valid() || (right.valid() && right.priority < left.priority) {
		return right
	}
	return left
}

func actionValid(value string) bool { return value == "ALLOW" || value == "DENY" }

type Policy struct {
	id            string
	defaultAction string
	minimum       int
	regex         [4]regexIndex
	groupRules    map[string]candidate
	times         timeIndex
	builder       *policyBuilder
}

type policyBuilder struct {
	regex      [4][]rawRegexRule
	groups     []rawGroupRule
	times      []timeRule
	priorities []int32
}

type tenantData struct {
	policy           *Policy
	departments      map[string]*Policy
	tenantGroups     *groupSet
	departmentGroups map[string]*groupSet
}

type groupSet struct {
	keysByAddress map[string][]string
	definedKeys   map[string]struct{}
}

type groupDefinition struct {
	key     string
	set     *groupSet
	members map[string]struct{}
}

type Engine struct {
	tenants     map[string]*tenantData
	DataVersion string
}

func newPolicy(id, defaultAction string) *Policy {
	return &Policy{id: id, defaultAction: defaultAction, builder: &policyBuilder{}}
}

func (engine *Engine) tenant(id string) *tenantData {
	value := engine.tenants[id]
	if value == nil {
		value = &tenantData{departments: make(map[string]*Policy), departmentGroups: make(map[string]*groupSet)}
		engine.tenants[id] = value
	}
	return value
}

func scopeFromPath(path string) (string, string, error) {
	if len(path) < 4 || path[0] != '/' {
		return "", "", fmt.Errorf("URL path must begin /{tenant_id}/{department_id}/: %q", path)
	}
	first := indexByteFrom(path, '/', 1)
	if first <= 1 {
		return "", "", fmt.Errorf("URL path must begin /{tenant_id}/{department_id}/: %q", path)
	}
	second := indexByteFrom(path, '/', first+1)
	if second <= first+1 {
		return "", "", fmt.Errorf("URL path must begin /{tenant_id}/{department_id}/: %q", path)
	}
	return path[1:first], path[first+1 : second], nil
}

func indexByteFrom(value string, target byte, start int) int {
	for index := start; index < len(value); index++ {
		if value[index] == target {
			return index
		}
	}
	return -1
}
