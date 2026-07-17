package com.costpilot.policy;

import java.util.UUID;

// Outcome of a runtime policy evaluation. executedModel differs from the
// requested model only on DOWNGRADE.
public record PolicyDecision(
		Decision decision,
		String executedModel,
		UUID matchedRuleId,
		String reason) {

	public enum Decision {
		ALLOW, DENY, DOWNGRADE, REQUIRE_APPROVAL
	}

	public static PolicyDecision allowDefault(String model) {
		return new PolicyDecision(Decision.ALLOW, model, null, "no policy rule governs this scope");
	}
}
