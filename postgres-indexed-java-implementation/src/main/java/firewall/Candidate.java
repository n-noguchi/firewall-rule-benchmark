package firewall;

public record Candidate(int priority, String ruleId, String action) {
    public static final Candidate EMPTY = new Candidate(0, "", "");

    public boolean isValid() {
        return priority > 0;
    }

    public static Candidate earlier(Candidate left, Candidate right) {
        if (!left.isValid() || (right.isValid() && right.priority() < left.priority())) {
            return right;
        }
        return left;
    }
}
