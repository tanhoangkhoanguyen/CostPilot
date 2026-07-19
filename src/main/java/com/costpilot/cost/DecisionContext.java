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

	// A cost-routing swap (7.2): the request declared a quality bar and the router picked
	// the cheapest policy-allowed model meeting it; reason says which bar and why.
	public static DecisionContext routed(LedgerContext ledger, String requestedModel, String executedModel,
			String reason) {
		return new DecisionContext(ledger, requestedModel, executedModel, PolicyDecision.Decision.ROUTE,
				reason, null, null, new AtomicReference<>());
	}

	// A rejection (DENY / REQUIRE_APPROVAL): nothing executes, so executedModel is null.
	public static DecisionContext rejected(LedgerContext ledger, String requestedModel,
			PolicyDecision.Decision decision, String reason, java.util.UUID matchedRuleId) {
		return new DecisionContext(ledger, requestedModel, null, decision, reason, matchedRuleId, null,
				new AtomicReference<>());
	}

	// A budget hard-block that escapes as 402 (no cheaper model fit): audited as a
	// denial with reason=budget and the scope that blocked it, so the 402 is explainable.
	public static DecisionContext budgetBlocked(LedgerContext ledger, String requestedModel, String blockedScope) {
		return new DecisionContext(ledger, requestedModel, null, PolicyDecision.Decision.DENY, "budget", null,
				blockedScope, new AtomicReference<>());
	}
}
