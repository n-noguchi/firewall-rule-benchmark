package main

import (
	"fmt"
	"regexp"
	"regexp/syntax"
	"sort"
	"strings"
)

type rawRegexRule struct {
	pattern   string
	candidate candidate
}

type rawGroupRule struct {
	key       string
	candidate candidate
}

type exactEntry struct {
	value     string
	candidate candidate
}

type compiledRule struct {
	expression *regexp.Regexp
	candidate  candidate
}

type literalRule struct {
	literal   string
	candidate candidate
}

type regexIndex struct {
	exact         []exactEntry
	prefixes      map[string][]compiledRule
	prefixLengths []int
	literals      []literalRule
	generic       []compiledRule
}

type regexShape int

const (
	regexGeneric regexShape = iota
	regexExact
	regexAnchoredPrefix
	regexLiteral
)

func classifyRegex(pattern string) (regexShape, string, error) {
	parsed, err := syntax.Parse(pattern, syntax.Perl)
	if err != nil {
		return regexGeneric, "", err
	}
	nodes := []*syntax.Regexp{parsed}
	if parsed.Op == syntax.OpConcat {
		nodes = parsed.Sub
	}
	if len(nodes) > 0 && nodes[0].Op == syntax.OpBeginText {
		var prefix strings.Builder
		position := 1
		for position < len(nodes) && nodes[position].Op == syntax.OpLiteral && nodes[position].Flags&syntax.FoldCase == 0 {
			prefix.WriteString(string(nodes[position].Rune))
			position++
		}
		if position == len(nodes)-1 && nodes[position].Op == syntax.OpEndText {
			return regexExact, prefix.String(), nil
		}
		if prefix.Len() > 0 {
			return regexAnchoredPrefix, prefix.String(), nil
		}
	}
	if parsed.Op == syntax.OpLiteral && parsed.Flags&syntax.FoldCase == 0 {
		return regexLiteral, string(parsed.Rune), nil
	}
	return regexGeneric, "", nil
}

func buildRegexIndex(raw []rawRegexRule) (regexIndex, error) {
	var result regexIndex
	if len(raw) == 0 {
		return result, nil
	}
	sort.Slice(raw, func(i, j int) bool {
		if raw[i].pattern == raw[j].pattern {
			return raw[i].candidate.priority < raw[j].candidate.priority
		}
		return raw[i].pattern < raw[j].pattern
	})
	deduplicated := raw[:0]
	for _, value := range raw {
		if len(deduplicated) == 0 || deduplicated[len(deduplicated)-1].pattern != value.pattern {
			deduplicated = append(deduplicated, value)
		}
	}
	lengths := make(map[int]struct{})
	for _, value := range deduplicated {
		shape, text, err := classifyRegex(value.pattern)
		if err != nil {
			return regexIndex{}, fmt.Errorf("invalid RE2 pattern %q: %w", value.pattern, err)
		}
		switch shape {
		case regexExact:
			result.exact = append(result.exact, exactEntry{value: text, candidate: value.candidate})
		case regexLiteral:
			result.literals = append(result.literals, literalRule{literal: text, candidate: value.candidate})
		case regexAnchoredPrefix:
			expression, err := regexp.Compile(value.pattern)
			if err != nil {
				return regexIndex{}, fmt.Errorf("invalid RE2 pattern %q: %w", value.pattern, err)
			}
			if result.prefixes == nil {
				result.prefixes = make(map[string][]compiledRule)
			}
			result.prefixes[text] = append(result.prefixes[text], compiledRule{expression: expression, candidate: value.candidate})
			lengths[len(text)] = struct{}{}
		default:
			expression, err := regexp.Compile(value.pattern)
			if err != nil {
				return regexIndex{}, fmt.Errorf("invalid RE2 pattern %q: %w", value.pattern, err)
			}
			result.generic = append(result.generic, compiledRule{expression: expression, candidate: value.candidate})
		}
	}
	sort.Slice(result.exact, func(i, j int) bool {
		if result.exact[i].value == result.exact[j].value {
			return result.exact[i].candidate.priority < result.exact[j].candidate.priority
		}
		return result.exact[i].value < result.exact[j].value
	})
	compact := result.exact[:0]
	for _, value := range result.exact {
		if len(compact) == 0 || compact[len(compact)-1].value != value.value {
			compact = append(compact, value)
		}
	}
	result.exact = compact
	for length := range lengths {
		result.prefixLengths = append(result.prefixLengths, length)
	}
	sort.Ints(result.prefixLengths)
	for key := range result.prefixes {
		sort.Slice(result.prefixes[key], func(i, j int) bool {
			return result.prefixes[key][i].candidate.priority < result.prefixes[key][j].candidate.priority
		})
	}
	sort.Slice(result.literals, func(i, j int) bool {
		return result.literals[i].candidate.priority < result.literals[j].candidate.priority
	})
	sort.Slice(result.generic, func(i, j int) bool {
		return result.generic[i].candidate.priority < result.generic[j].candidate.priority
	})
	return result, nil
}

func (index *regexIndex) match(input string, best candidate) candidate {
	position := sort.Search(len(index.exact), func(i int) bool { return index.exact[i].value >= input })
	if position < len(index.exact) && index.exact[position].value == input {
		best = earlier(best, index.exact[position].candidate)
	}
	for _, length := range index.prefixLengths {
		if length > len(input) {
			break
		}
		for _, rule := range index.prefixes[input[:length]] {
			if best.valid() && rule.candidate.priority >= best.priority {
				break
			}
			if rule.expression.MatchString(input) {
				best = rule.candidate
				break
			}
		}
	}
	for _, rule := range index.literals {
		if best.valid() && rule.candidate.priority >= best.priority {
			break
		}
		if strings.Contains(input, rule.literal) {
			best = rule.candidate
			break
		}
	}
	for _, rule := range index.generic {
		if best.valid() && rule.candidate.priority >= best.priority {
			break
		}
		if rule.expression.MatchString(input) {
			best = rule.candidate
			break
		}
	}
	return best
}

type timeRule struct {
	start     int
	end       int
	candidate candidate
}

type timeIndex struct {
	rules        []timeRule
	candidates   []candidate
	bestBySecond []int32
}

func buildTimeIndex(raw []timeRule) timeIndex {
	if len(raw) == 0 {
		return timeIndex{}
	}
	sort.Slice(raw, func(i, j int) bool {
		if raw[i].start != raw[j].start {
			return raw[i].start < raw[j].start
		}
		if raw[i].end != raw[j].end {
			return raw[i].end < raw[j].end
		}
		return raw[i].candidate.priority < raw[j].candidate.priority
	})
	deduplicated := raw[:0]
	for _, value := range raw {
		if len(deduplicated) == 0 || deduplicated[len(deduplicated)-1].start != value.start || deduplicated[len(deduplicated)-1].end != value.end {
			deduplicated = append(deduplicated, value)
		}
	}
	if len(deduplicated) < 64 {
		sort.Slice(deduplicated, func(i, j int) bool { return deduplicated[i].candidate.priority < deduplicated[j].candidate.priority })
		return timeIndex{rules: deduplicated}
	}
	result := timeIndex{rules: nil, candidates: make([]candidate, len(deduplicated)), bestBySecond: make([]int32, 86_400)}
	for index := range result.bestBySecond {
		result.bestBySecond[index] = -1
	}
	sort.Slice(deduplicated, func(i, j int) bool { return deduplicated[i].candidate.priority < deduplicated[j].candidate.priority })
	next := make([]int, 86_401)
	for index := range next {
		next[index] = index
	}
	var find func(int) int
	find = func(value int) int {
		for next[value] != value {
			next[value] = next[next[value]]
			value = next[value]
		}
		return value
	}
	fill := func(start, end, candidateIndex int) {
		for second := find(start); second < end; second = find(second) {
			result.bestBySecond[second] = int32(candidateIndex)
			next[second] = find(second + 1)
		}
	}
	for index, rule := range deduplicated {
		result.candidates[index] = rule.candidate
		if rule.start < rule.end {
			fill(rule.start, rule.end, index)
		} else {
			fill(rule.start, 86_400, index)
			fill(0, rule.end, index)
		}
	}
	return result
}

func (index *timeIndex) match(second int, best candidate) candidate {
	if len(index.bestBySecond) != 0 {
		position := index.bestBySecond[second]
		if position >= 0 {
			return earlier(best, index.candidates[position])
		}
		return best
	}
	for _, rule := range index.rules {
		if best.valid() && rule.candidate.priority >= best.priority {
			break
		}
		matches := rule.start < rule.end && rule.start <= second && second < rule.end
		matches = matches || rule.start > rule.end && (rule.start <= second || second < rule.end)
		if matches {
			return rule.candidate
		}
	}
	return best
}
