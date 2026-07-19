package firewall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Time-range rule index. For fewer than 64 rules we keep a priority-sorted
 * list; for larger sets we materialise an 86,400-second point index using a
 * union-find fill so each lookup is O(1). Matches the Go adaptive strategy.
 */
final class TimeIndex {
    private final List<TimeRule> rules;
    private final Candidate[] candidates;
    private final int[] bestBySecond; // index into candidates, or -1

    private TimeIndex(List<TimeRule> rules, Candidate[] candidates, int[] bestBySecond) {
        this.rules = rules;
        this.candidates = candidates;
        this.bestBySecond = bestBySecond;
    }

    static TimeIndex empty() {
        return new TimeIndex(new ArrayList<>(), new Candidate[0], new int[0]);
    }

    Candidate match(int second, Candidate best) {
        if (bestBySecond.length != 0) {
            int position = bestBySecond[second];
            if (position >= 0) {
                return Candidate.earlier(best, candidates[position]);
            }
            return best;
        }
        for (TimeRule rule : rules) {
            if (best.isValid() && rule.candidate.priority() >= best.priority()) {
                break;
            }
            if (matches(rule, second)) {
                return rule.candidate;
            }
        }
        return best;
    }

    private static boolean matches(TimeRule rule, int second) {
        if (rule.start < rule.end) {
            return rule.start <= second && second < rule.end;
        }
        return rule.start <= second || second < rule.end;
    }

    record TimeRule(int start, int end, Candidate candidate) {}

    static final class Builder {
        private final List<TimeRule> rules = new ArrayList<>();

        void add(int start, int end, Candidate candidate) {
            rules.add(new TimeRule(start, end, candidate));
        }

        TimeIndex build() {
            if (rules.isEmpty()) {
                return TimeIndex.empty();
            }
            // Deduplicate by (start, end) keeping the lowest priority.
            rules.sort((a, b) -> {
                if (a.start != b.start) return Integer.compare(a.start, b.start);
                if (a.end != b.end) return Integer.compare(a.end, b.end);
                return Integer.compare(a.candidate.priority(), b.candidate.priority());
            });
            List<TimeRule> deduplicated = new ArrayList<>();
            for (TimeRule rule : rules) {
                if (deduplicated.isEmpty()) {
                    deduplicated.add(rule);
                    continue;
                }
                TimeRule previous = deduplicated.get(deduplicated.size() - 1);
                if (previous.start == rule.start && previous.end == rule.end) {
                    continue;
                }
                deduplicated.add(rule);
            }
            if (deduplicated.size() < 64) {
                deduplicated.sort((a, b) -> Integer.compare(a.candidate.priority(), b.candidate.priority()));
                return new TimeIndex(deduplicated, new Candidate[0], new int[0]);
            }
            deduplicated.sort((a, b) -> Integer.compare(a.candidate.priority(), b.candidate.priority()));
            Candidate[] candidates = new Candidate[deduplicated.size()];
            int[] bestBySecond = new int[86_400];
            Arrays.fill(bestBySecond, -1);
            int[] next = new int[86_401];
            for (int i = 0; i < next.length; i++) {
                next[i] = i;
            }
            for (int index = 0; index < deduplicated.size(); index++) {
                TimeRule rule = deduplicated.get(index);
                candidates[index] = rule.candidate;
                if (rule.start < rule.end) {
                    fill(bestBySecond, next, rule.start, rule.end, index);
                } else {
                    fill(bestBySecond, next, rule.start, 86_400, index);
                    fill(bestBySecond, next, 0, rule.end, index);
                }
            }
            return new TimeIndex(new ArrayList<>(), candidates, bestBySecond);
        }

        private static void fill(int[] bestBySecond, int[] next, int start, int end, int candidateIndex) {
            int second = find(next, start);
            while (second < end) {
                bestBySecond[second] = candidateIndex;
                next[second] = find(next, second + 1);
                second = find(next, next[second]);
            }
        }

        private static int find(int[] next, int value) {
            while (next[value] != value) {
                next[value] = next[next[value]];
                value = next[value];
            }
            return value;
        }
    }
}
