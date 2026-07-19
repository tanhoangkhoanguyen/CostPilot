package com.costpilot.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Stage 8: a REQUIRE_APPROVAL request parked for a human decision. Holds the full
 * request context (attribution + payload) so the request can be replayed verbatim on
 * approval and survives a restart. The state machine is pending -> approved | rejected
 * | expired; only the pending -> * transition is allowed, once.
 */
@Entity
@Table(name = "pending_approval")
public class PendingApproval {

	public enum State {
		pending, approved, rejected, expired
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

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

	@Column(name = "idempotency_key", nullable = false, columnDefinition = "text")
	private String idempotencyKey;

	@Column(name = "requested_model", nullable = false, columnDefinition = "text")
	private String requestedModel;

	@Column(name = "min_tier")
	private Integer minTier;

	// JSON string of the canonical request (messages, maxTokens, stream)
	@Column(name = "request_payload", nullable = false, columnDefinition = "text")
	private String requestPayload;

	@Column(name = "estimate_nanos")
	private Long estimateNanos;

	@Column(columnDefinition = "text")
	private String reason;

	@Column(name = "matched_rule_id")
	private UUID matchedRuleId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, columnDefinition = "text")
	private State state = State.pending;

	// rendered response captured on approval, as a JSON string
	@Column(name = "stored_response", columnDefinition = "text")
	private String storedResponse;

	@Column(name = "decided_by", columnDefinition = "text")
	private String decidedBy;

	@Column(name = "decision_reason", columnDefinition = "text")
	private String decisionReason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected PendingApproval() {
	}

	public PendingApproval(String tenantId, String teamId, String projectId, String userId, String environment,
			String idempotencyKey, String requestedModel, Integer minTier, String requestPayload,
			Long estimateNanos, String reason, UUID matchedRuleId, Instant expiresAt) {
		this.tenantId = tenantId;
		this.teamId = teamId;
		this.projectId = projectId;
		this.userId = userId;
		this.environment = environment;
		this.idempotencyKey = idempotencyKey;
		this.requestedModel = requestedModel;
		this.minTier = minTier;
		this.requestPayload = requestPayload;
		this.estimateNanos = estimateNanos;
		this.reason = reason;
		this.matchedRuleId = matchedRuleId;
		this.expiresAt = expiresAt;
	}

	/** Mark a terminal decision. Caller guarantees the row is still pending. */
	public void decide(State terminal, String decidedBy, String decisionReason, Instant when) {
		this.state = terminal;
		this.decidedBy = decidedBy;
		this.decisionReason = decisionReason;
		this.decidedAt = when;
	}

	public UUID getId() {
		return id;
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

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestedModel() {
		return requestedModel;
	}

	public Integer getMinTier() {
		return minTier;
	}

	public String getRequestPayload() {
		return requestPayload;
	}

	public Long getEstimateNanos() {
		return estimateNanos;
	}

	public String getReason() {
		return reason;
	}

	public UUID getMatchedRuleId() {
		return matchedRuleId;
	}

	public State getState() {
		return state;
	}

	public String getStoredResponse() {
		return storedResponse;
	}

	public void setStoredResponse(String storedResponse) {
		this.storedResponse = storedResponse;
	}

	public String getDecidedBy() {
		return decidedBy;
	}

	public String getDecisionReason() {
		return decisionReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}
}
