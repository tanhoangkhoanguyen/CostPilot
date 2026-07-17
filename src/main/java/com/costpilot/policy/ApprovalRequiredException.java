package com.costpilot.policy;

// Placeholder until Stage 8 parks REQUIRE_APPROVAL requests for a human; for now
// the caller gets a machine-readable rejection.
public class ApprovalRequiredException extends RuntimeException {

	private final PolicyDecision decision;

	public ApprovalRequiredException(PolicyDecision decision, String model) {
		super("model " + model + " requires approval: " + decision.reason());
		this.decision = decision;
	}

	public PolicyDecision getDecision() {
		return decision;
	}
}
