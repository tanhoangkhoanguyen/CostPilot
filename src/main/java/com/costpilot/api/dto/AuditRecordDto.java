package com.costpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.costpilot.domain.AuditRecord;

// Read model for the admin audit query (5.1). Cost is rendered as a plain decimal
// string so no float/precision is lost crossing JSON.
public record AuditRecordDto(
		UUID id,
		UUID usageRecordId,
		String tenantId,
		String teamId,
		String projectId,
		String userId,
		String environment,
		String requestedModel,
		String executedModel,
		String decision,
		String reason,
		UUID matchedRuleId,
		String blockedScope,
		String finishReason,
		String provider,
		Integer inputTokens,
		Integer outputTokens,
		String cost,
		String idempotencyKey,
		Instant createdAt) {

	public static AuditRecordDto from(AuditRecord a) {
		return new AuditRecordDto(
				a.getId(), a.getUsageRecordId(), a.getTenantId(), a.getTeamId(), a.getProjectId(),
				a.getUserId(), a.getEnvironment(), a.getRequestedModel(), a.getExecutedModel(),
				a.getDecision(), a.getReason(), a.getMatchedRuleId(), a.getBlockedScope(),
				a.getFinishReason(), a.getProvider(), a.getInputTokens(), a.getOutputTokens(),
				a.getCost() != null ? a.getCost().toPlainString() : null,
				a.getIdempotencyKey(), a.getCreatedAt());
	}
}
