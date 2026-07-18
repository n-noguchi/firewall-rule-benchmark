package main

import (
	"testing"
)

func TestClassifyRegex(t *testing.T) {
	tests := []struct {
		pattern string
		shape   regexShape
		text    string
	}{
		{`^192\.0\.2\.10$`, regexExact, "192.0.2.10"},
		{`^https://trusted\.example/`, regexAnchoredPrefix, "https://trusted.example/"},
		{`Chrome`, regexLiteral, "Chrome"},
		{`(?i)^chrome`, regexGeneric, ""},
		{`^(abc|def)$`, regexGeneric, ""},
		{`^$`, regexExact, ""},
	}
	for _, test := range tests {
		shape, text, err := classifyRegex(test.pattern)
		if err != nil || shape != test.shape || text != test.text {
			t.Fatalf("classifyRegex(%q) = (%v, %q, %v), want (%v, %q)", test.pattern, shape, text, err, test.shape, test.text)
		}
	}
}

func TestRegexIndexReturnsEarliestAcrossRepresentations(t *testing.T) {
	index, err := buildRegexIndex([]rawRegexRule{
		{pattern: `Chrome`, candidate: candidate{priority: 30, ruleID: "literal"}},
		{pattern: `^Mozilla.*`, candidate: candidate{priority: 20, ruleID: "prefix"}},
		{pattern: `^Mozilla Chrome$`, candidate: candidate{priority: 10, ruleID: "exact"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	got := index.match("Mozilla Chrome", candidate{})
	if got.ruleID != "exact" {
		t.Fatalf("got %+v", got)
	}
}

func TestAdaptiveTimeIndexUsesFirstMatch(t *testing.T) {
	rules := make([]timeRule, 0, 64)
	for index := 0; index < 64; index++ {
		rules = append(rules, timeRule{start: index, end: index + 100, candidate: candidate{priority: 100 + index, ruleID: "later"}})
	}
	rules = append(rules, timeRule{start: 10, end: 20, candidate: candidate{priority: 1, ruleID: "first"}})
	index := buildTimeIndex(rules)
	if len(index.bestBySecond) != 86_400 {
		t.Fatal("large time-rule set did not build the dense point index")
	}
	if got := index.match(15, candidate{}); got.ruleID != "first" {
		t.Fatalf("got %+v", got)
	}
}
