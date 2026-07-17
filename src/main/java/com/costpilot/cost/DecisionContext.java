package com.costpilot.cost;

import java.util.concurrent.atomic.AtomicReference;

import com.costpilot.policy.PolicyDecision;

// Carries the governance "why" for one request down to the ledger/audit write seam
// (5.1). The controller knows the decision, requested-vs-executed model and downgrade
// reason before forwarding; the mid-stream cutoff (4.3) is only known later, inside the
// reactive forwarding chain - so finishReason is a single-writer AtomicReference set once
// at cutoff and read once in doFinally. Everything else is immutable, wrapping the
// existing LedgerContext (attribution) rather than replacing it.
public record DecisionContext(
		LedgerContext ledger,
		String requestedModel,
		String executedModel,
		PolicyDecision.Decision decision,
		String reason,
		java.util.UUID matchedRuleId,
		String blockedScope,
		AtomicReference<String> finishReason) {

	// An ALLOW with no downgrade: requested == executed, no reason/rule/blocked scope.
	public static DecisionContext allow(LedgerContext ledger, String model) {
		return new DecisionContext(ledger, model, model, PolicyDecision.Decision.ALLOW,
				null, null, null, new AtomicReference<>());
	}

	// A downgrade (policy or budget): requested differs from executed; reason explains why.
	public static DecisionContext downgrade(LedgerContext ledger, String requestedModel, String executedModel,
			String reason, java.util.UUID matchedRuleId, String blockedScope) {
		return new DecisionContext(ledger, requestedModel, executedModel, PolicyDecision.Decision.DOWNGRADE,
				reason, matchedRuleId, blockedScope, new AtomicReference<>());
	}
}
