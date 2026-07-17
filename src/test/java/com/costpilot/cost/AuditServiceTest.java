package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.costpilot.core.model.Usage;
import com.costpilot.domain.AuditRecord;
import com.costpilot.domain.AuditRecordRepository;
import com.costpilot.domain.UsageRecord;
import com.costpilot.policy.PolicyDecision;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

	@Mock
	private AuditRecordRepository repository;

	@InjectMocks
	private AuditService auditService;

	@Captor
	private ArgumentCaptor<AuditRecord> captor;

	private static LedgerContext ledger() {
		return new LedgerContext(null, "team-a", "proj-a", "user-a", "prod", "idem-1");
	}

	@Test
	void allowMapsIdentityCostTokensAndDefaultFinishStop() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		DecisionContext decision = DecisionContext.allow(ledger(), "gpt-4o");
		UsageRecord usageRow = new UsageRecord(null, "team-a", "proj-a", "user-a", "prod",
				"openai", "gpt-4o", 100, 50, new BigDecimal("0.0030"), null, "idem-1");

		auditService.recordForwarded(decision, "openai", new Usage(100, 50), new BigDecimal("0.0030"), usageRow);

		verifySaved();
		AuditRecord saved = captor.getValue();
		assertThat(saved.getDecision()).isEqualTo("allow");
		assertThat(saved.getRequestedModel()).isEqualTo("gpt-4o");
		assertThat(saved.getExecutedModel()).isEqualTo("gpt-4o");
		assertThat(saved.getProvider()).isEqualTo("openai");
		assertThat(saved.getInputTokens()).isEqualTo(100);
		assertThat(saved.getOutputTokens()).isEqualTo(50);
		assertThat(saved.getCost()).isEqualByComparingTo("0.0030");
		assertThat(saved.getFinishReason()).isEqualTo("stop");
		assertThat(saved.getUsageRecordId()).isEqualTo(usageRow.getId());
	}

	@Test
	void downgradeCarriesReasonAndOriginalVsExecuted() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		UUID rule = UUID.randomUUID();
		DecisionContext decision = DecisionContext.downgrade(ledger(), "gpt-4o", "gpt-4o-mini", "policy", rule, null);

		auditService.recordForwarded(decision, "openai", new Usage(10, 5), new BigDecimal("0.0001"), null);

		verifySaved();
		AuditRecord saved = captor.getValue();
		assertThat(saved.getDecision()).isEqualTo("downgrade");
		assertThat(saved.getRequestedModel()).isEqualTo("gpt-4o");
		assertThat(saved.getExecutedModel()).isEqualTo("gpt-4o-mini");
		assertThat(saved.getReason()).isEqualTo("policy");
		assertThat(saved.getMatchedRuleId()).isEqualTo(rule);
		assertThat(saved.getUsageRecordId()).isNull(); // unpriced forward: no ledger row
	}

	@Test
	void cutoffFinishReasonPropagatesToAuditRow() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		DecisionContext decision = DecisionContext.allow(ledger(), "gpt-4o");
		decision.finishReason().set("budget_cutoff");

		auditService.recordForwarded(decision, "openai", new Usage(100, 900), new BigDecimal("0.05"), null);

		verifySaved();
		assertThat(captor.getValue().getFinishReason()).isEqualTo("budget_cutoff");
	}

	@Test
	void denyMapsToRejectedRowWithNoExecutedModelOrCost() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		DecisionContext decision = DecisionContext.rejected(ledger(), "gpt-4o",
				PolicyDecision.Decision.DENY, "model not in allowed set", UUID.randomUUID());

		auditService.recordRejected(decision);

		verifySaved();
		AuditRecord saved = captor.getValue();
		assertThat(saved.getDecision()).isEqualTo("deny");
		assertThat(saved.getExecutedModel()).isNull();
		assertThat(saved.getCost()).isNull();
		assertThat(saved.getInputTokens()).isNull();
	}

	@Test
	void requireApprovalMapsToRejectedRow() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		DecisionContext decision = DecisionContext.rejected(ledger(), "gpt-4o",
				PolicyDecision.Decision.REQUIRE_APPROVAL, "over threshold", null);

		auditService.recordRejected(decision);

		verifySaved();
		assertThat(captor.getValue().getDecision()).isEqualTo("require_approval");
	}

	private void verifySaved() {
		org.mockito.Mockito.verify(repository).save(captor.capture());
	}
}
