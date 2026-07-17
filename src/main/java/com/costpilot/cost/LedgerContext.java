package com.costpilot.cost;

// Attribution for one governed request as it travels toward the ledger.
// Identity fields are header-sourced strings until auth (6.1) resolves real ids.
public record LedgerContext(
		String tenantId,
		String teamId,
		String projectId,
		String userId,
		String environment,
		String idempotencyKey) {
}
