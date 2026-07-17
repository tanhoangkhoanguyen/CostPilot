package com.costpilot.cost;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.costpilot.budget.BudgetScope;
import com.costpilot.budget.BudgetService;
import com.costpilot.core.model.Usage;
import com.costpilot.domain.UsageRecord;
import com.costpilot.domain.UsageRecordRepository;

// The single write path into the money ledger. Correctness does NOT depend on the
// existence pre-check (that's just a fast path) - the unique constraint on
// idempotency_key is the real guarantee, and a lost race surfaces as
// DataIntegrityViolationException which we treat as "already recorded".
@Service
public class UsageLedgerService {

	private static final Logger log = LoggerFactory.getLogger(UsageLedgerService.class);

	private final UsageRecordRepository repository;
	private final BudgetService budgetService;

	public UsageLedgerService(UsageRecordRepository repository, BudgetService budgetService) {
		this.repository = repository;
		this.budgetService = budgetService;
	}

	public UsageRecord record(LedgerContext context, String provider, String model, Usage usage, Cost cost,
			UUID priceId) {
		if (repository.existsByIdempotencyKey(context.idempotencyKey())) {
			log.info("ledger replay ignored idempotencyKey={}", context.idempotencyKey());
			return repository.findByIdempotencyKey(context.idempotencyKey()).orElseThrow();
		}
		try {
			UsageRecord saved = insert(context, provider, model, usage, cost, priceId);
			// live budget counters move only on a FRESH insert - ledger idempotency
			// is exactly what makes the counters replay-safe (3.1)
			budgetService.charge(BudgetScope.TENANT, saved.getTenantId(), cost.total());
			budgetService.charge(BudgetScope.TEAM, saved.getTeamId(), cost.total());
			budgetService.charge(BudgetScope.PROJECT, saved.getProjectId(), cost.total());
			budgetService.charge(BudgetScope.MODEL, saved.getModel(), cost.total());
			return saved;
		} catch (DataIntegrityViolationException e) {
			// concurrent replay lost the insert race - the money is already counted
			log.info("ledger replay ignored (insert race) idempotencyKey={}", context.idempotencyKey());
			return repository.findByIdempotencyKey(context.idempotencyKey()).orElseThrow();
		}
	}

	// saveAndFlush runs in its own transaction (SimpleJpaRepository is @Transactional);
	// a duplicate key surfaces here as DataIntegrityViolationException on flush
	private UsageRecord insert(LedgerContext context, String provider, String model, Usage usage, Cost cost,
			UUID priceId) {
		UsageRecord record = new UsageRecord(
				context.tenantId(), context.teamId(), context.projectId(), context.userId(),
				context.environment(), provider, model,
				usage.inputTokens(), usage.outputTokens(), cost.total(), priceId, context.idempotencyKey());
		UsageRecord saved = repository.saveAndFlush(record);
		log.info("ledger write id={} provider={} model={} cost={} idempotencyKey={}",
				saved.getId(), provider, model, cost.total().toPlainString(), context.idempotencyKey());
		return saved;
	}
}
