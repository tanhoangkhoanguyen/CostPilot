package com.costpilot.analytics.dto;

// Reconciliation over a fixed window: Postgres ledger vs ClickHouse OLAP, compared as
// exact integer nanodollars (and row counts). reconciled is true only when both match -
// the proof that the OLAP pipeline did not lose or double-count spend.
public record ReconciliationResult(
		long postgresRows,
		long clickhouseRows,
		long postgresNanos,
		long clickhouseNanos,
		boolean reconciled) {
}
