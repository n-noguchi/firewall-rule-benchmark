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
    private final String[] exactValues;
    private final Candidate[] exactCandidates;
    private final Map<String, List<CompiledRule>> prefixes;
    private final int[] prefixLengths;
    private final List<LiteralRule> literals;
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
        return new RegexIndex(new String[0], new Candidate[0], new HashMap<String, List<CompiledRule>>(),
                new int[0], new ArrayList<LiteralRule>(), new ArrayList<CompiledRule>());
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

    static final class CompiledRule {
        final Pattern pattern;
        final Candidate candidate;

        CompiledRule(Pattern pattern, Candidate candidate) {
            this.pattern = pattern;
            this.candidate = candidate;
        }
    }

    static final class LiteralRule {
        final String literal;
        final Candidate candidate;

        LiteralRule(String literal, Candidate candidate) {
            this.literal = literal;
            this.candidate = candidate;
        }
    }

    static final class Builder {
        private final Map<String, Candidate> exact = new TreeMap<String, Candidate>();
        private final Map<String, List<CompiledRule>> prefixes = new HashMap<String, List<CompiledRule>>();
        private final List<LiteralRule> literals = new ArrayList<LiteralRule>();
        private final List<CompiledRule> generic = new ArrayList<CompiledRule>();

        void add(String pattern, Candidate candidate) {
            Classification classification = classify(pattern);
            int shape = classification.shape;
            String text = classification.text;
            if (shape == SHAPE_EXACT) {
                Candidate previous = exact.get(text);
                if (previous == null || candidate.priority() < previous.priority()) {
                    exact.put(text, candidate);
                }
            } else if (shape == SHAPE_LITERAL) {
                literals.add(new LiteralRule(text, candidate));
            } else if (shape == SHAPE_ANCHORED_PREFIX) {
                Pattern compiled = Pattern.compile(pattern);
                List<CompiledRule> bucket = prefixes.get(text);
                if (bucket == null) {
                    bucket = new ArrayList<CompiledRule>();
                    prefixes.put(text, bucket);
                }
                bucket.add(new CompiledRule(compiled, candidate));
            } else {
                Pattern compiled = Pattern.compile(pattern);
                generic.add(new CompiledRule(compiled, candidate));
            }
        }

        RegexIndex build() {
            String[] exactValues = exact.keySet().toArray(new String[0]);
            Candidate[] exactCandidates = new Candidate[exactValues.length];
            int index = 0;
            for (String key : exact.keySet()) {
                exactCandidates[index++] = exact.get(key);
            }
            for (List<CompiledRule> bucket : prefixes.values()) {
                bucket.sort(new java.util.Comparator<CompiledRule>() {
                    @Override
                    public int compare(CompiledRule a, CompiledRule b) {
                        return Integer.compare(a.candidate.priority(), b.candidate.priority());
                    }
                });
            }
            java.util.TreeSet<Integer> lengthSet = new java.util.TreeSet<Integer>();
            for (String key : prefixes.keySet()) {
                lengthSet.add(key.length());
            }
            int[] prefixLengths = new int[lengthSet.size()];
            int i = 0;
            for (Integer value : lengthSet) {
                prefixLengths[i++] = value;
            }
            literals.sort(new java.util.Comparator<LiteralRule>() {
                @Override
                public int compare(LiteralRule a, LiteralRule b) {
                    return Integer.compare(a.candidate.priority(), b.candidate.priority());
                }
            });
            generic.sort(new java.util.Comparator<CompiledRule>() {
                @Override
                public int compare(CompiledRule a, CompiledRule b) {
                    return Integer.compare(a.candidate.priority(), b.candidate.priority());
                }
            });
            return new RegexIndex(exactValues, exactCandidates, prefixes, prefixLengths, literals, generic);
        }
    }

    static final int SHAPE_GENERIC = 0;
    static final int SHAPE_EXACT = 1;
    static final int SHAPE_ANCHORED_PREFIX = 2;
    static final int SHAPE_LITERAL = 3;

    private static final class Classification {
        final int shape;
        final String text;

        Classification(int shape, String text) {
            this.shape = shape;
            this.text = text;
        }
    }

    private static Classification classify(String pattern) {
        if (pattern.isEmpty()) {
            return new Classification(SHAPE_GENERIC, "");
        }
        if (pattern.indexOf("(?") >= 0) {
            return new Classification(SHAPE_GENERIC, "");
        }
        int start = 0;
        boolean anchoredStart = pattern.charAt(0) == '^';
        if (anchoredStart) {
            start = 1;
        }
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
            return new Classification(SHAPE_EXACT, literal.toString());
        }
        if (!anchoredStart && !sawEndAnchor && entireLiteral && reachedEnd) {
            return new Classification(SHAPE_LITERAL, literal.toString());
        }
        if (anchoredStart && literal.length() > 0) {
            return new Classification(SHAPE_ANCHORED_PREFIX, literal.toString());
        }
        return new Classification(SHAPE_GENERIC, "");
    }

    private static boolean isMetaChar(char c) {
        return c == '.' || c == '*' || c == '+' || c == '?' || c == '|'
                || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '^';
    }

    private static boolean isEscapeMeta(char c) {
        return c == 'd' || c == 'D' || c == 'w' || c == 'W' || c == 's' || c == 'S'
                || c == 'b' || c == 'B' || c == 'A' || c == 'z' || c == 'Z';
    }
}
