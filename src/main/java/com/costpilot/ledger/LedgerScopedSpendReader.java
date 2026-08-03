package com.costpilot.ledger;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.costpilot.budget.BudgetScope;
import com.costpilot.budget.ScopedSpendReader;

/**
 * Ledger-backed {@link ScopedSpendReader}. The scope-to-query switch moved here verbatim
 * from {@code BudgetService.spentFromLedger}, so the numbers are unchanged; only the
 * direction of the package dependency is.
 */
@Component
class LedgerScopedSpendReader implements ScopedSpendReader {

	private final UsageRecordRepository usage;

	LedgerScopedSpendReader(UsageRecordRepository usage) {
		this.usage = usage;
	}

	@Override
	public BigDecimal totalCostFor(BudgetScope scope, String ref) {
		return switch (scope) {
			case TENANT -> usage.totalCostForTenant(ref);
			case TEAM -> usage.totalCostForTeam(ref);
			case PROJECT -> usage.totalCostForProject(ref);
			case MODEL -> usage.totalCostForModel(ref);
		};
	}
}
