package com.costpilot.analytics.dto;

// 7.3: money saved by cost-routing / downgrading over the window. Sourced from the
// Postgres ledger (usage_record.savings_nanos), so it reconciles with money truth - not
// from ClickHouse. actualSpendUsd is what the executed models cost; wouldBeSpendUsd is
// what the originally-requested models would have cost (actual + savings).
public record SavingsSummary(
		String routingSavingsUsd,
		String actualSpendUsd,
		String wouldBeSpendUsd) {
}
