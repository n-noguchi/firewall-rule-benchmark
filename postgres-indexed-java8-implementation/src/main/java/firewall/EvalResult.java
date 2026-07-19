package firewall;

public final class EvalResult {
    public String accessId;
    public String selectedPolicyId;
    public String matchedRuleId;
    public String action;

    public EvalResult() {
    }

    public EvalResult(String accessId, String selectedPolicyId, String matchedRuleId, String action) {
        this.accessId = accessId;
        this.selectedPolicyId = selectedPolicyId;
        this.matchedRuleId = matchedRuleId;
        this.action = action;
    }
}
