package com.costpilot.budget;

import java.math.BigDecimal;

/**
 * Settled spend for one budget scope, as recorded in the ledger.
 *
 * <p>A port, owned by {@code budget} and implemented in {@code ledger}. {@link BudgetService}
 * used to inject {@code UsageRecordRepository} directly, which made {@code budget} depend on
 * {@code ledger} while {@code ledger} already depends on {@code budget} to settle
 * reservations - the last package cycle left after #100, and the one
 * {@code ./gradlew packageCycles} would otherwise reject.
 *
 * <p>Inverting it here is also the honest direction: budgets define what "spend for a scope"
 * means; the ledger merely knows how to total it.
 */
public interface ScopedSpendReader {

	/**
	 * Total settled cost recorded against {@code ref} for the given scope.
	 *
	 * @return the total, never null - an unknown or unused ref reads as zero
	 */
	BigDecimal totalCostFor(BudgetScope scope, String ref);
}
