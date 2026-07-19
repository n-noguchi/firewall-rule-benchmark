package firewall;

public final class Candidate {
    public static final Candidate EMPTY = new Candidate(0, "", "");

    private final int priority;
    private final String ruleId;
    private final String action;

    public Candidate(int priority, String ruleId, String action) {
        this.priority = priority;
        this.ruleId = ruleId;
        this.action = action;
    }

    public int priority() {
        return priority;
    }

    public String ruleId() {
        return ruleId;
    }

    public String action() {
        return action;
    }

    public boolean isValid() {
        return priority > 0;
    }

    public static Candidate earlier(Candidate left, Candidate right) {
        if (!left.isValid() || (right.isValid() && right.priority() < left.priority)) {
            return right;
        }
        return left;
    }
}
