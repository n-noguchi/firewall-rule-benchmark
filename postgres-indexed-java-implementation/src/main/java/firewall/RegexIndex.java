package firewall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Indexes regex rules by their structural shape so that simple patterns (exact
 * match, anchored prefix, plain substring) can be answered without invoking
 * the regex engine. Mirrors the design of the Go priority-indexed engine.
 */
final class RegexIndex {
    /** Sorted by value for binary search. Stores the lowest-priority candidate per value. */
    private final String[] exactValues;
    private final Candidate[] exactCandidates;

    /** Anchored prefixes bucketed by their literal prefix, then sorted by priority. */
    private final Map<String, List<CompiledRule>> prefixes;
    private final int[] prefixLengths;

    /** Plain substring rules sorted by priority (ascending). */
    private final List<LiteralRule> literals;

    /** Generic patterns sorted by priority (ascending). */
    private final List<CompiledRule> generic;

    private RegexIndex(String[] exactValues, Candidate[] exactCandidates,
                       Map<String, List<CompiledRule>> prefixes, int[] prefixLengths,
                       List<LiteralRule> literals, List<CompiledRule> generic) {
        this.exactValues = exactValues;
        this.exactCandidates = exactCandidates;
        this.prefixes = prefixes;
        this.prefixLengths = prefixLengths;
        this.literals = literals;
        this.generic = generic;
    }

    static RegexIndex empty() {
        return new RegexIndex(new String[0], new Candidate[0], new HashMap<>(), new int[0],
                new ArrayList<>(), new ArrayList<>());
    }

    Candidate match(String input, Candidate best) {
        int position = Arrays.binarySearch(exactValues, input);
        if (position >= 0) {
            best = Candidate.earlier(best, exactCandidates[position]);
        }
        for (int length : prefixLengths) {
            if (length > input.length()) {
                break;
            }
            String prefix = input.substring(0, length);
            List<CompiledRule> rules = prefixes.get(prefix);
            if (rules == null) {
                continue;
            }
            for (CompiledRule rule : rules) {
                if (best.isValid() && rule.candidate.priority() >= best.priority()) {
                    break;
                }
                if (rule.pattern.matcher(input).find()) {
                    best = rule.candidate;
                    break;
                }
            }
        }
        for (LiteralRule rule : literals) {
            if (best.isValid() && rule.candidate.priority() >= best.priority()) {
                break;
            }
            if (input.contains(rule.literal)) {
                best = rule.candidate;
                break;
            }
        }
        for (CompiledRule rule : generic) {
            if (best.isValid() && rule.candidate.priority() >= best.priority()) {
                break;
            }
            if (rule.pattern.matcher(input).find()) {
                best = rule.candidate;
                break;
            }
        }
        return best;
    }

    enum Shape { GENERIC, EXACT, ANCHORED_PREFIX, LITERAL }

    record CompiledRule(Pattern pattern, Candidate candidate) {}
    record LiteralRule(String literal, Candidate candidate) {}

    static final class Builder {
        private final Map<String, Candidate> exact = new TreeMap<>();
        private final Map<String, List<CompiledRule>> prefixes = new HashMap<>();
        private final List<LiteralRule> literals = new ArrayList<>();
        private final List<CompiledRule> generic = new ArrayList<>();

        void add(String pattern, Candidate candidate) {
            Classification classification = classify(pattern);
            switch (classification.shape) {
                case EXACT -> {
                    Candidate previous = exact.get(classification.text);
                    if (previous == null || candidate.priority() < previous.priority()) {
                        exact.put(classification.text, candidate);
                    }
                }
                case LITERAL -> literals.add(new LiteralRule(classification.text, candidate));
                case ANCHORED_PREFIX -> {
                    Pattern compiled = Pattern.compile(pattern);
                    prefixes.computeIfAbsent(classification.text, k -> new ArrayList<>())
                            .add(new CompiledRule(compiled, candidate));
                }
                default -> {
                    Pattern compiled = Pattern.compile(pattern);
                    generic.add(new CompiledRule(compiled, candidate));
                }
            }
        }

        RegexIndex build() {
            String[] exactValues = exact.keySet().toArray(new String[0]);
            Candidate[] exactCandidates = new Candidate[exactValues.length];
            int index = 0;
            for (String key : exact.keySet()) {
                exactCandidates[index++] = exact.get(key);
            }
            // prefixes bucketed and sorted by priority
            for (List<CompiledRule> bucket : prefixes.values()) {
                bucket.sort((a, b) -> Integer.compare(a.candidate.priority(), b.candidate.priority()));
            }
            int[] prefixLengths = prefixes.keySet().stream()
                    .mapToInt(String::length)
                    .distinct()
                    .sorted()
                    .toArray();
            literals.sort((a, b) -> Integer.compare(a.candidate.priority(), b.candidate.priority()));
            generic.sort((a, b) -> Integer.compare(a.candidate.priority(), b.candidate.priority()));
            return new RegexIndex(exactValues, exactCandidates, prefixes, prefixLengths,
                    literals, generic);
        }
    }

    private record Classification(Shape shape, String text) {}

    private static Classification classify(String pattern) {
        if (pattern.isEmpty()) {
            return new Classification(Shape.GENERIC, "");
        }
        // Embedded inline flags such as (?i) make folding semantics possible; fall back to generic.
        if (pattern.indexOf("(?") >= 0) {
            return new Classification(Shape.GENERIC, "");
        }
        int start = 0;
        boolean anchoredStart = pattern.charAt(0) == '^';
        if (anchoredStart) {
            start = 1;
        }
        // Scan leading literal portion (handling escapes).
        StringBuilder literal = new StringBuilder();
        int position = start;
        boolean entireLiteral = true;
        boolean sawEndAnchor = false;
        while (position < pattern.length()) {
            char c = pattern.charAt(position);
            if (c == '\\' && position + 1 < pattern.length()) {
                char next = pattern.charAt(position + 1);
                if (isEscapeMeta(next)) {
                    entireLiteral = false;
                    break;
                }
                literal.append(next);
                position += 2;
                continue;
            }
            if (c == '$' && position == pattern.length() - 1) {
                sawEndAnchor = true;
                position++;
                break;
            }
            if (isMetaChar(c)) {
                entireLiteral = false;
                break;
            }
            literal.append(c);
            position++;
        }
        boolean reachedEnd = position == pattern.length();
        if (anchoredStart && sawEndAnchor && reachedEnd) {
            return new Classification(Shape.EXACT, literal.toString());
        }
        if (!anchoredStart && !sawEndAnchor && entireLiteral && reachedEnd) {
            return new Classification(Shape.LITERAL, literal.toString());
        }
        if (anchoredStart && literal.length() > 0) {
            return new Classification(Shape.ANCHORED_PREFIX, literal.toString());
        }
        return new Classification(Shape.GENERIC, "");
    }

    private static boolean isMetaChar(char c) {
        return switch (c) {
            case '.', '*', '+', '?', '|', '(', ')', '[', ']', '{', '}', '^' -> true;
            default -> false;
        };
    }

    /** Returns true if '\X' represents a character class or assertion (so the leading literal stops). */
    private static boolean isEscapeMeta(char c) {
        return switch (c) {
            case 'd', 'D', 'w', 'W', 's', 'S', 'b', 'B', 'A', 'z', 'Z' -> true;
            default -> false;
        };
    }
}
