package com.costpilot.policy;

public class PolicyDeniedException extends RuntimeException {

	private final PolicyDecision decision;

	public PolicyDeniedException(PolicyDecision decision, String model) {
		super("model " + model + " is not allowed by policy: " + decision.reason());
		this.decision = decision;
	}

	public PolicyDecision getDecision() {
		return decision;
	}
}
