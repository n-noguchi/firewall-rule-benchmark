package firewall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled firewall policy. Each policy keeps four regex indexes (source IPv4,
 * URL path, referer, user-agent), a group-key to candidate map, and a time
 * index. The minimum rule priority is cached so that an exact match at the
 * minimum priority can short-circuit the remaining checks.
 */
final class Policy {
    static final int SOURCE_IPV4 = 0;
    static final int URL_PATH = 1;
    static final int REFERER = 2;
    static final int USER_AGENT = 3;

    final String id;
    final String defaultAction;
    int minimum = Integer.MAX_VALUE;
    final RegexIndex[] regex;
    Map<String, Candidate> groupRules;
    TimeIndex times;

    /** Populated only during loading; cleared once the policy is finalised. */
    Builder builder;

    Policy(String id, String defaultAction) {
        this.id = id;
        this.defaultAction = defaultAction;
        this.regex = new RegexIndex[]{
                RegexIndex.empty(), RegexIndex.empty(), RegexIndex.empty(), RegexIndex.empty()
        };
        this.groupRules = new HashMap<String, Candidate>();
        this.times = TimeIndex.empty();
        this.builder = new Builder();
    }

    static final class Builder {
        final List<RegexRuleRaw>[] regex;
        final List<GroupRuleRaw> groups = new ArrayList<GroupRuleRaw>();
        final List<TimeIndex.TimeRule> times = new ArrayList<TimeIndex.TimeRule>();
        final List<Integer> priorities = new ArrayList<Integer>();

        @SuppressWarnings("unchecked")
        Builder() {
            regex = new List[]{
                    new ArrayList<RegexRuleRaw>(), new ArrayList<RegexRuleRaw>(),
                    new ArrayList<RegexRuleRaw>(), new ArrayList<RegexRuleRaw>()
            };
        }
    }

    static final class RegexRuleRaw {
        final String pattern;
        final Candidate candidate;

        RegexRuleRaw(String pattern, Candidate candidate) {
            this.pattern = pattern;
            this.candidate = candidate;
        }
    }

    static final class GroupRuleRaw {
        final String key;
        final Candidate candidate;

        GroupRuleRaw(String key, Candidate candidate) {
            this.key = key;
            this.candidate = candidate;
        }
    }
}
