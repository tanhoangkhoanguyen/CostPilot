package com.costpilot.cost;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.core.model.Usage;
import com.costpilot.domain.AuditRecord;
import com.costpilot.domain.AuditRecordRepository;
import com.costpilot.domain.UsageRecord;
import com.costpilot.policy.PolicyDecision;

// 5.1: writes the append-only audit row that explains one governed decision. Two entry
// points because denied/approval requests never forward (no usage row exists) while
// forwarded requests carry cost + tokens + a ledger link.
@Service
public class AuditService {

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	private final AuditRecordRepository repository;

	public AuditService(AuditRecordRepository repository) {
		this.repository = repository;
	}

	// Forwarded request (ALLOW / DOWNGRADE): full detail incl. cost + tokens + ledger
	// link. usageRecord is null for an unpriced forward (no ledger row); then cost and
	// tokens fall back to what the caller passes (may be null/zero).
	public AuditRecord recordForwarded(DecisionContext decision, String provider, Usage usage, BigDecimal cost,
			UsageRecord usageRecord) {
		AuditRecord row = base(decision)
				.provider(provider)
				.finishReason(decision.finishReason().get() != null ? decision.finishReason().get() : "stop")
				.usageRecordId(usageRecord != null ? usageRecord.getId() : null)
				.inputTokens(usage != null ? usage.inputTokens() : null)
				.outputTokens(usage != null ? usage.outputTokens() : null)
				.cost(cost)
				.build();
		AuditRecord saved = repository.save(row);
		log.info("audit forwarded id={} decision={} requested={} executed={} finish={} cost={}",
				saved.getId(), saved.getDecision(), saved.getRequestedModel(), saved.getExecutedModel(),
				saved.getFinishReason(), cost != null ? cost.toPlainString() : null);
		return saved;
	}

	// Rejected request (DENY / REQUIRE_APPROVAL): no forwarding, no cost. executedModel
	// stays null - nothing ran.
	public AuditRecord recordRejected(DecisionContext decision) {
		AuditRecord row = base(decision).executedModel(null).build();
		AuditRecord saved = repository.save(row);
		log.info("audit rejected id={} decision={} requested={} reason={}",
				saved.getId(), saved.getDecision(), saved.getRequestedModel(), saved.getReason());
		return saved;
	}

	private AuditRecord.Builder base(DecisionContext decision) {
		LedgerContext ctx = decision.ledger();
		return AuditRecord.builder()
				.tenantId(ctx.tenantId())
				.teamId(ctx.teamId())
				.projectId(ctx.projectId())
				.userId(ctx.userId())
				.environment(ctx.environment())
				.idempotencyKey(ctx.idempotencyKey())
				.requestedModel(decision.requestedModel())
				.executedModel(decision.executedModel())
				.decision(dbValue(decision.decision()))
				.reason(decision.reason())
				.matchedRuleId(decision.matchedRuleId())
				.blockedScope(decision.blockedScope());
	}

	// enum -> the lowercase token the audit_record.decision check constraint allows
	private static String dbValue(PolicyDecision.Decision decision) {
		return switch (decision) {
			case ALLOW -> "allow";
			case DOWNGRADE -> "downgrade";
			case DENY -> "deny";
			case REQUIRE_APPROVAL -> "require_approval";
		};
	}
}
