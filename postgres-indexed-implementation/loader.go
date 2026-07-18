package main

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
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

func LoadEngine(dataDirectory string) (*Engine, error) {
	return loadEngine(csvMasterSource{directory: dataDirectory})
}

type masterSource interface {
	dataVersion() (string, error)
	readPolicies(func([]string) error) error
	readRules(func([]string) error) error
	readGroups(func([]string) error) error
	readMembers(func([]string) error) error
}

type csvMasterSource struct{ directory string }

func (source csvMasterSource) dataVersion() (string, error) {
	return readDatasetVersion(source.directory)
}

func readDatasetVersion(dataDirectory string) (string, error) {
	manifest, err := os.ReadFile(filepath.Join(dataDirectory, "manifest.json"))
	if err != nil {
		return "", fmt.Errorf("read dataset manifest: %w", err)
	}
	var metadata struct {
		DatasetVersion string `json:"dataset_version"`
	}
	if err := json.Unmarshal(manifest, &metadata); err != nil || metadata.DatasetVersion == "" {
		return "", errors.New("invalid dataset manifest")
	}
	return metadata.DatasetVersion, nil
}

func (source csvMasterSource) readPolicies(consume func([]string) error) error {
	return readCSVDirectory(filepath.Join(source.directory, "policies"), policyHeader, consume)
}

func (source csvMasterSource) readRules(consume func([]string) error) error {
	return readCSVDirectory(filepath.Join(source.directory, "firewall_rules"), ruleHeader, consume)
}

func (source csvMasterSource) readGroups(consume func([]string) error) error {
	return readCSVDirectory(filepath.Join(source.directory, "source_ip_groups"), groupHeader, consume)
}

func (source csvMasterSource) readMembers(consume func([]string) error) error {
	return readCSVDirectory(filepath.Join(source.directory, "source_ip_group_members"), memberHeader, consume)
}

func loadEngine(source masterSource) (*Engine, error) {
	dataVersion, err := source.dataVersion()
	if err != nil {
		return nil, err
	}
	engine := &Engine{tenants: make(map[string]*tenantData), DataVersion: dataVersion}
	policiesByID := make(map[string]*Policy)
	policyScopes := make(map[string]struct{})
	if err := source.readPolicies(func(row []string) error {
		id, tenantID, departmentID := row[0], row[1], row[2]
		enabled, enabledErr := strconv.ParseBool(row[4])
		if id == "" || tenantID == "" || !actionValid(row[3]) || enabledErr != nil {
			return fmt.Errorf("invalid or unsupported policy %q", id)
		}
		if _, exists := policiesByID[id]; exists {
			return fmt.Errorf("duplicate policy_id %q", id)
		}
		scope := tenantID + "\x00" + departmentID
		if _, exists := policyScopes[scope]; exists {
			return fmt.Errorf("duplicate policy scope for %s/%s", tenantID, departmentID)
		}
		policyScopes[scope] = struct{}{}
		policy := newPolicy(id, row[3])
		policiesByID[id] = policy
		if !enabled {
			return nil
		}
		tenant := engine.tenant(tenantID)
		if departmentID == "" {
			if tenant.policy != nil {
				return fmt.Errorf("duplicate tenant policy for %q", tenantID)
			}
			tenant.policy = policy
		} else {
			if tenant.departments[departmentID] != nil {
				return fmt.Errorf("duplicate department policy for %s/%s", tenantID, departmentID)
			}
			tenant.departments[departmentID] = policy
		}
		return nil
	}); err != nil {
		return nil, err
	}
	if err := source.readRules(func(row []string) error {
		id := row[0]
		policy := policiesByID[row[1]]
		priority64, priorityErr := strconv.ParseInt(row[2], 10, 32)
		enabled, enabledErr := strconv.ParseBool(row[5])
		if id == "" || policy == nil || priorityErr != nil || priority64 < 1 || enabledErr != nil || !actionValid(row[4]) {
			return fmt.Errorf("invalid or unsupported rule %q", id)
		}
		priority := int(priority64)
		value := candidate{priority: priority, ruleID: id, action: row[4]}
		policy.builder.priorities = append(policy.builder.priorities, int32(priority))
		switch row[3] {
		case "SOURCE_IPV4_REGEX":
			if !enabled {
				return validateRegexRule(id, row[6])
			}
			return appendRegexRule(policy, 0, row[6], value)
		case "URL_PATH_REGEX":
			if !enabled {
				return validateRegexRule(id, row[6])
			}
			return appendRegexRule(policy, 1, row[6], value)
		case "REFERER_REGEX":
			if !enabled {
				return validateRegexRule(id, row[6])
			}
			return appendRegexRule(policy, 2, row[6], value)
		case "USER_AGENT_REGEX":
			if !enabled {
				return validateRegexRule(id, row[6])
			}
			return appendRegexRule(policy, 3, row[6], value)
		case "SOURCE_IPV4_GROUP":
			if row[7] == "" {
				return fmt.Errorf("rule %q: source_ip_group_key is required", id)
			}
			if enabled {
				policy.builder.groups = append(policy.builder.groups, rawGroupRule{key: row[7], candidate: value})
			}
		case "ACCESS_TIME_RANGE":
			start, err := timeOfDaySeconds(row[8])
			if err != nil {
				return fmt.Errorf("rule %q: invalid start_time_utc: %w", id, err)
			}
			end, err := timeOfDaySeconds(row[9])
			if err != nil {
				return fmt.Errorf("rule %q: invalid end_time_utc: %w", id, err)
			}
			if start == end {
				return fmt.Errorf("rule %q: start_time_utc and end_time_utc must differ", id)
			}
			if enabled {
				policy.builder.times = append(policy.builder.times, timeRule{start: start, end: end, candidate: value})
			}
		default:
			return fmt.Errorf("rule %q: unknown rule_type %q", id, row[3])
		}
		return nil
	}); err != nil {
		return nil, err
	}
	groupsByID := make(map[string]*groupDefinition)
	if err := source.readGroups(func(row []string) error {
		id, tenantID, departmentID, key := row[0], row[1], row[2], row[3]
		if id == "" || tenantID == "" || key == "" {
			return fmt.Errorf("invalid source IP group %q", id)
		}
		if _, exists := groupsByID[id]; exists {
			return fmt.Errorf("duplicate group_id %q", id)
		}
		tenant := engine.tenant(tenantID)
		var set *groupSet
		if departmentID == "" {
			if tenant.tenantGroups == nil {
				tenant.tenantGroups = newGroupSet()
			}
			set = tenant.tenantGroups
		} else {
			set = tenant.departmentGroups[departmentID]
			if set == nil {
				set = newGroupSet()
				tenant.departmentGroups[departmentID] = set
			}
		}
		if _, exists := set.definedKeys[key]; exists {
			return fmt.Errorf("duplicate group_key %q in %s/%s", key, tenantID, departmentID)
		}
		set.definedKeys[key] = struct{}{}
		groupsByID[id] = &groupDefinition{key: key, set: set, members: make(map[string]struct{})}
		return nil
	}); err != nil {
		return nil, err
	}
	if err := source.readMembers(func(row []string) error {
		definition := groupsByID[row[0]]
		address := row[1]
		if definition == nil || address == "" {
			return fmt.Errorf("member references unknown group %q", row[0])
		}
		if _, exists := definition.members[address]; exists {
			return fmt.Errorf("duplicate member %q in group %q", address, row[0])
		}
		definition.members[address] = struct{}{}
		definition.set.keysByAddress[address] = append(definition.set.keysByAddress[address], definition.key)
		return nil
	}); err != nil {
		return nil, err
	}
	for _, policy := range policiesByID {
		if err := finalizePolicy(policy); err != nil {
			return nil, fmt.Errorf("policy %q: %w", policy.id, err)
		}
	}
	return engine, nil
}

func newGroupSet() *groupSet {
	return &groupSet{keysByAddress: make(map[string][]string), definedKeys: make(map[string]struct{})}
}

func appendRegexRule(policy *Policy, field int, pattern string, value candidate) error {
	if pattern == "" {
		return fmt.Errorf("rule %q: regex_pattern is required", value.ruleID)
	}
	policy.builder.regex[field] = append(policy.builder.regex[field], rawRegexRule{pattern: pattern, candidate: value})
	return nil
}

func validateRegexRule(ruleID, pattern string) error {
	if pattern == "" {
		return fmt.Errorf("rule %q: regex_pattern is required", ruleID)
	}
	return nil
}

func finalizePolicy(policy *Policy) error {
	builder := policy.builder
	sort.Slice(builder.priorities, func(i, j int) bool { return builder.priorities[i] < builder.priorities[j] })
	if len(builder.priorities) > 0 {
		policy.minimum = int(builder.priorities[0])
	}
	for index := 1; index < len(builder.priorities); index++ {
		if builder.priorities[index] == builder.priorities[index-1] {
			return fmt.Errorf("duplicate priority %d", builder.priorities[index])
		}
	}
	for index := range builder.regex {
		built, err := buildRegexIndex(builder.regex[index])
		if err != nil {
			return err
		}
		policy.regex[index] = built
	}
	if len(builder.groups) != 0 {
		sort.Slice(builder.groups, func(i, j int) bool {
			if builder.groups[i].key == builder.groups[j].key {
				return builder.groups[i].candidate.priority < builder.groups[j].candidate.priority
			}
			return builder.groups[i].key < builder.groups[j].key
		})
		policy.groupRules = make(map[string]candidate)
		for _, value := range builder.groups {
			if _, exists := policy.groupRules[value.key]; !exists {
				policy.groupRules[value.key] = value.candidate
			}
		}
	}
	policy.times = buildTimeIndex(builder.times)
	policy.builder = nil
	return nil
}

func timeOfDaySeconds(value string) (int, error) {
	parsed, err := time.Parse("15:04:05", value)
	if err != nil {
		return 0, err
	}
	return parsed.Hour()*3600 + parsed.Minute()*60 + parsed.Second(), nil
}

func csvFiles(directory string) ([]string, error) {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return nil, err
	}
	files := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.EqualFold(filepath.Ext(entry.Name()), ".csv") {
			files = append(files, filepath.Join(directory, entry.Name()))
		}
	}
	sort.Strings(files)
	if len(files) == 0 {
		return nil, fmt.Errorf("no CSV files in %s", directory)
	}
	return files, nil
}

func readCSVDirectory(directory string, expectedHeader []string, consume func([]string) error) error {
	files, err := csvFiles(directory)
	if err != nil {
		return err
	}
	for _, path := range files {
		stream, err := os.Open(path)
		if err != nil {
			return err
		}
		reader := csv.NewReader(stream)
		reader.FieldsPerRecord = len(expectedHeader)
		header, readErr := reader.Read()
		if readErr == nil && !equalStrings(header, expectedHeader) {
			readErr = fmt.Errorf("invalid CSV header %v", header)
		}
		for readErr == nil {
			row, rowErr := reader.Read()
			if errors.Is(rowErr, io.EOF) {
				break
			}
			if rowErr != nil {
				readErr = rowErr
				break
			}
			if err := consume(row); err != nil {
				readErr = err
				break
			}
		}
		closeErr := stream.Close()
		if readErr != nil {
			return fmt.Errorf("%s: %w", path, readErr)
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func equalStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}
