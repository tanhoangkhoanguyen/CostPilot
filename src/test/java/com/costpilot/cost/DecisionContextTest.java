package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.costpilot.policy.PolicyDecision;

class DecisionContextTest {

	private static LedgerContext ledger() {
		return new LedgerContext(null, "team-a", "proj-a", "user-a", "prod", UUID.randomUUID().toString());
	}

	@Test
	void allowKeepsRequestedEqualToExecutedAndNoReason() {
		DecisionContext ctx = DecisionContext.allow(ledger(), "gpt-4o");

		assertThat(ctx.decision()).isEqualTo(PolicyDecision.Decision.ALLOW);
		assertThat(ctx.requestedModel()).isEqualTo("gpt-4o");
		assertThat(ctx.executedModel()).isEqualTo("gpt-4o");
		assertThat(ctx.reason()).isNull();
		assertThat(ctx.matchedRuleId()).isNull();
		assertThat(ctx.blockedScope()).isNull();
		assertThat(ctx.finishReason().get()).isNull();
	}

	@Test
	void downgradeRecordsOriginalVsExecutedAndWhy() {
		UUID rule = UUID.randomUUID();
		DecisionContext ctx = DecisionContext.downgrade(ledger(), "gpt-4o", "gpt-4o-mini", "policy", rule, null);

		assertThat(ctx.decision()).isEqualTo(PolicyDecision.Decision.DOWNGRADE);
		assertThat(ctx.requestedModel()).isEqualTo("gpt-4o");
		assertThat(ctx.executedModel()).isEqualTo("gpt-4o-mini");
		assertThat(ctx.reason()).isEqualTo("policy");
		assertThat(ctx.matchedRuleId()).isEqualTo(rule);
	}

	@Test
	void budgetDowngradeCarriesBlockedScope() {
		DecisionContext ctx = DecisionContext.downgrade(ledger(), "gpt-4o", "gpt-4o-mini", "budget", null, "team");

		assertThat(ctx.reason()).isEqualTo("budget");
		assertThat(ctx.blockedScope()).isEqualTo("team");
		assertThat(ctx.matchedRuleId()).isNull();
	}

	// The one mutable field: set exactly once at mid-stream cutoff (4.3), read once in
	// doFinally. compareAndSet-style single-writer semantics - a second set is a no-op we
	// never make, but the reference must survive being shared across the reactive chain.
	@Test
	void finishReasonIsSettableOnceAndVisible() {
		DecisionContext ctx = DecisionContext.allow(ledger(), "gpt-4o");

		assertThat(ctx.finishReason().get()).isNull();
		ctx.finishReason().set("budget_cutoff");
		assertThat(ctx.finishReason().get()).isEqualTo("budget_cutoff");
	}
}
