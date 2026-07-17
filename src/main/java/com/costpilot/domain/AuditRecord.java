package com.costpilot.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 5.1: one row per governed request, explaining the spending decision. Superset of
// usage_record - it also covers DENY / REQUIRE_APPROVAL requests that never forward,
// so most cost/token fields are nullable. Columns mirror V7__audit_record.sql exactly
// (ddl-auto=validate).
@Entity
@Table(name = "audit_record")
public class AuditRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	// links to the money ledger row when one exists; null for denied/approval/unpriced
	@Column(name = "usage_record_id")
	private UUID usageRecordId;

	@Column(name = "tenant_id", columnDefinition = "text")
	private String tenantId;

	@Column(name = "team_id", columnDefinition = "text")
	private String teamId;

	@Column(name = "project_id", columnDefinition = "text")
	private String projectId;

	@Column(name = "user_id", columnDefinition = "text")
	private String userId;

	@Column(columnDefinition = "text")
	private String environment;

	@Column(name = "requested_model", nullable = false, columnDefinition = "text")
	private String requestedModel;

	@Column(name = "executed_model", columnDefinition = "text")
	private String executedModel;

	@Column(nullable = false, columnDefinition = "text")
	private String decision;

	@Column(columnDefinition = "text")
	private String reason;

	@Column(name = "matched_rule_id")
	private UUID matchedRuleId;

	@Column(name = "blocked_scope", columnDefinition = "text")
	private String blockedScope;

	@Column(name = "finish_reason", columnDefinition = "text")
	private String finishReason;

	@Column(columnDefinition = "text")
	private String provider;

	@Column(name = "input_tokens")
	private Integer inputTokens;

	@Column(name = "output_tokens")
	private Integer outputTokens;

	@Column(precision = 18, scale = 9)
	private BigDecimal cost;

	@Column(name = "idempotency_key", columnDefinition = "text")
	private String idempotencyKey;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected AuditRecord() {
	}

	public UUID getId() {
		return id;
	}

	public UUID getUsageRecordId() {
		return usageRecordId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getTeamId() {
		return teamId;
	}

	public String getProjectId() {
		return projectId;
	}

	public String getUserId() {
		return userId;
	}

	public String getEnvironment() {
		return environment;
	}

	public String getRequestedModel() {
		return requestedModel;
	}

	public String getExecutedModel() {
		return executedModel;
	}

	public String getDecision() {
		return decision;
	}

	public String getReason() {
		return reason;
	}

	public UUID getMatchedRuleId() {
		return matchedRuleId;
	}

	public String getBlockedScope() {
		return blockedScope;
	}

	public String getFinishReason() {
		return finishReason;
	}

	public String getProvider() {
		return provider;
	}

	public Integer getInputTokens() {
		return inputTokens;
	}

	public Integer getOutputTokens() {
		return outputTokens;
	}

	public BigDecimal getCost() {
		return cost;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	// Built via the fluent builder below - the field set is wide and mostly optional
	// (denied requests have no cost/tokens/executed model), so a constructor would be
	// an unreadable wall of nulls at the call site.
	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private final AuditRecord r = new AuditRecord();

		public Builder usageRecordId(UUID v) { r.usageRecordId = v; return this; }
		public Builder tenantId(String v) { r.tenantId = v; return this; }
		public Builder teamId(String v) { r.teamId = v; return this; }
		public Builder projectId(String v) { r.projectId = v; return this; }
		public Builder userId(String v) { r.userId = v; return this; }
		public Builder environment(String v) { r.environment = v; return this; }
		public Builder requestedModel(String v) { r.requestedModel = v; return this; }
		public Builder executedModel(String v) { r.executedModel = v; return this; }
		public Builder decision(String v) { r.decision = v; return this; }
		public Builder reason(String v) { r.reason = v; return this; }
		public Builder matchedRuleId(UUID v) { r.matchedRuleId = v; return this; }
		public Builder blockedScope(String v) { r.blockedScope = v; return this; }
		public Builder finishReason(String v) { r.finishReason = v; return this; }
		public Builder provider(String v) { r.provider = v; return this; }
		public Builder inputTokens(Integer v) { r.inputTokens = v; return this; }
		public Builder outputTokens(Integer v) { r.outputTokens = v; return this; }
		public Builder cost(BigDecimal v) { r.cost = v; return this; }
		public Builder idempotencyKey(String v) { r.idempotencyKey = v; return this; }

		public AuditRecord build() {
			return r;
		}
	}
}
