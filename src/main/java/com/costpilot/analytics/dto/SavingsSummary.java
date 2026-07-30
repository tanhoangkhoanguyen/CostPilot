package com.costpilot.analytics.dto;

// 7.3 / 1.3 (#93): money saved over the window. Routing/downgrade savings come from
// usage_record.savings_nanos; cache-hit savings come from cache_hit_log. Both are
// Postgres money truth (not ClickHouse / not Prometheus alone).
//
// actualSpendUsd  = what executed models cost (ledger cost; cache hits contribute $0)
// wouldBeSpendUsd = actual + routing + cache  (counterfactual without CostPilot)
// percentSaved    = totalSavings / wouldBeSpend * 100, or null when would-be is $0
public record SavingsSummary(
		String routingSavingsUsd,
		String cacheSavingsUsd,
		String totalSavingsUsd,
		String actualSpendUsd,
		String wouldBeSpendUsd,
		Double percentSaved) {
}
