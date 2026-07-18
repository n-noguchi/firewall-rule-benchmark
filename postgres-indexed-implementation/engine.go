package main

import (
	"errors"
	"fmt"
	"runtime"
	"sync"
	"time"
)

func (engine *Engine) Evaluate(access AccessLog) (Result, error) {
	if access.AccessID == "" || access.SourceIPv4 == "" || access.URLPath == "" || access.AccessTimestampUTC == "" {
		return Result{}, errors.New("access_id, source_ipv4, url_path, and access_timestamp_utc are required")
	}
	tenantID, departmentID, err := scopeFromPath(access.URLPath)
	if err != nil {
		return Result{}, err
	}
	tenant := engine.tenants[tenantID]
	if tenant == nil {
		return Result{}, fmt.Errorf("no policy exists for tenant %q", tenantID)
	}
	policy := tenant.departments[departmentID]
	if policy == nil {
		policy = tenant.policy
	}
	if policy == nil {
		return Result{}, fmt.Errorf("no policy exists for tenant %q", tenantID)
	}
	parsedTime, err := time.Parse(time.RFC3339Nano, access.AccessTimestampUTC)
	if err != nil {
		return Result{}, fmt.Errorf("invalid access_timestamp_utc: %w", err)
	}
	seconds := parsedTime.UTC().Hour()*3600 + parsedTime.UTC().Minute()*60 + parsedTime.UTC().Second()

	var best candidate
	best = policy.regex[0].match(access.SourceIPv4, best)
	if best.valid() && best.priority == policy.minimum {
		return matchedResult(access.AccessID, policy.id, best), nil
	}
	set := tenant.departmentGroups[departmentID]
	if set == nil {
		set = tenant.tenantGroups
	}
	if set != nil {
		for _, key := range set.keysByAddress[access.SourceIPv4] {
			if value, exists := policy.groupRules[key]; exists {
				best = earlier(best, value)
			}
		}
	}
	if best.valid() && best.priority == policy.minimum {
		return matchedResult(access.AccessID, policy.id, best), nil
	}
	best = policy.regex[1].match(access.URLPath, best)
	best = policy.times.match(seconds, best)
	best = policy.regex[2].match(access.Referer, best)
	best = policy.regex[3].match(access.UserAgent, best)
	if best.valid() {
		return matchedResult(access.AccessID, policy.id, best), nil
	}
	return Result{AccessID: access.AccessID, SelectedPolicyID: policy.id, Action: policy.defaultAction}, nil
}

func matchedResult(accessID, policyID string, value candidate) Result {
	return Result{AccessID: accessID, SelectedPolicyID: policyID, MatchedRuleID: value.ruleID, Action: value.action}
}

func (engine *Engine) EvaluateMany(accesses []AccessLog, workers int) ([]Result, error) {
	if workers <= 0 {
		workers = runtime.GOMAXPROCS(0)
	}
	if workers > len(accesses) {
		workers = len(accesses)
	}
	if workers < 2 || len(accesses) < 512 {
		results := make([]Result, len(accesses))
		for index, access := range accesses {
			value, err := engine.Evaluate(access)
			if err != nil {
				return nil, fmt.Errorf("evaluate %s: %w", access.AccessID, err)
			}
			results[index] = value
		}
		return results, nil
	}
	results := make([]Result, len(accesses))
	var wait sync.WaitGroup
	errorsFound := make(chan error, workers)
	chunk := (len(accesses) + workers - 1) / workers
	for worker := 0; worker < workers; worker++ {
		start := worker * chunk
		end := start + chunk
		if end > len(accesses) {
			end = len(accesses)
		}
		if start >= end {
			break
		}
		wait.Add(1)
		go func() {
			defer wait.Done()
			for index := start; index < end; index++ {
				value, err := engine.Evaluate(accesses[index])
				if err != nil {
					select {
					case errorsFound <- fmt.Errorf("evaluate %s: %w", accesses[index].AccessID, err):
					default:
					}
					return
				}
				results[index] = value
			}
		}()
	}
	wait.Wait()
	select {
	case err := <-errorsFound:
		return nil, err
	default:
		return results, nil
	}
}
