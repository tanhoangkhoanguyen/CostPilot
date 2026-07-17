package com.costpilot.core.model;

import java.time.Instant;
import java.util.UUID;

// 5.2: the append-only usage event published to Kafka after the ledger write. Carries
// everything the OLAP side (5.3/5.4) needs to reconcile with the Postgres ledger and
// explain a decision - who, model (original vs executed), decision, tokens, cost.
//
// Cost is integer nanodollars (long), never a float, so it stays exact across the wire
// and reconciles bit-for-bit with the ledger. timestamp is the ledger row's created_at
// so windowed reconciliation lines up with usage_record.
public record UsageEvent(
		UUID eventId,
		String tenantId,
		String teamId,
		String projectId,
		String userId,
		String environment,
		String provider,
		String originalModel,
		String executedModel,
		String decision,
		String finishReason,
		int inputTokens,
		int outputTokens,
		long costNanos,
		Instant timestamp) {
}
