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
    final RegexIndex[] regex = new RegexIndex[]{
            RegexIndex.empty(), RegexIndex.empty(), RegexIndex.empty(), RegexIndex.empty()
    };
    Map<String, Candidate> groupRules = new HashMap<>();
    TimeIndex times = TimeIndex.empty();

    /** Populated only during loading; cleared once the policy is finalised. */
    Builder builder;

    Policy(String id, String defaultAction) {
        this.id = id;
        this.defaultAction = defaultAction;
        this.builder = new Builder();
    }

    static final class Builder {
        @SuppressWarnings("unchecked")
        final List<RegexRuleRaw>[] regex = new List[]{
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()
        };
        final List<GroupRuleRaw> groups = new ArrayList<>();
        final List<TimeIndex.TimeRule> times = new ArrayList<>();
        final List<Integer> priorities = new ArrayList<>();
    }

    record RegexRuleRaw(String pattern, Candidate candidate) {}
    record GroupRuleRaw(String key, Candidate candidate) {}
}
