package com.costpilot.policy;

import java.util.UUID;

// Outcome of a runtime policy evaluation. executedModel differs from the
// requested model only on DOWNGRADE. approvalThresholdNanos (8.1) is the matched
// rule's cost gate, carried so the controller can escalate an otherwise-ALLOW
// request to REQUIRE_APPROVAL once it has the pre-flight estimate; null = no gate.
public record PolicyDecision(
		Decision decision,
		String executedModel,
		UUID matchedRuleId,
		String reason,
		Long approvalThresholdNanos) {

	public PolicyDecision(Decision decision, String executedModel, UUID matchedRuleId, String reason) {
		this(decision, executedModel, matchedRuleId, reason, null);
	}

	public enum Decision {
		// ROUTE (7.2) is produced by the cost router, never by policy evaluation itself
		ALLOW, DENY, DOWNGRADE, REQUIRE_APPROVAL, ROUTE
	}

	public static PolicyDecision allowDefault(String model) {
		return new PolicyDecision(Decision.ALLOW, model, null, "no policy rule governs this scope");
	}
}
