package com.costpilot.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy_rule")
public class PolicyRule {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "scope_type", nullable = false, columnDefinition = "text")
	private String scopeType;

	@Column(name = "scope_ref", nullable = false, columnDefinition = "text")
	private String scopeRef;

	@Column(name = "allowed_models", nullable = false, columnDefinition = "text")
	private String allowedModels;

	@Column(name = "fallback_action", nullable = false, columnDefinition = "text")
	private String fallbackAction = "deny";

	@Column(name = "downgrade_to", columnDefinition = "text")
	private String downgradeTo;

	// 8.1: a request whose pre-flight MAX estimate exceeds this (exact nanodollars)
	// requires human approval regardless of model. Null = no cost gate on this rule.
	@Column(name = "approval_threshold_nanos")
	private Long approvalThresholdNanos;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected PolicyRule() {
	}

	public PolicyRule(String scopeType, String scopeRef, String allowedModels,
			String fallbackAction, String downgradeTo) {
		this(scopeType, scopeRef, allowedModels, fallbackAction, downgradeTo, null);
	}

	public PolicyRule(String scopeType, String scopeRef, String allowedModels,
			String fallbackAction, String downgradeTo, Long approvalThresholdNanos) {
		this.scopeType = scopeType;
		this.scopeRef = scopeRef;
		this.allowedModels = allowedModels;
		this.fallbackAction = fallbackAction;
		this.downgradeTo = downgradeTo;
		this.approvalThresholdNanos = approvalThresholdNanos;
	}

	public void update(String allowedModels, String fallbackAction, String downgradeTo) {
		update(allowedModels, fallbackAction, downgradeTo, this.approvalThresholdNanos);
	}

	public void update(String allowedModels, String fallbackAction, String downgradeTo,
			Long approvalThresholdNanos) {
		this.allowedModels = allowedModels;
		this.fallbackAction = fallbackAction;
		this.downgradeTo = downgradeTo;
		this.approvalThresholdNanos = approvalThresholdNanos;
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getScopeType() {
		return scopeType;
	}

	public String getScopeRef() {
		return scopeRef;
	}

	public String getAllowedModels() {
		return allowedModels;
	}

	public String getFallbackAction() {
		return fallbackAction;
	}

	public String getDowngradeTo() {
		return downgradeTo;
	}

	public Long getApprovalThresholdNanos() {
		return approvalThresholdNanos;
	}

	public boolean isActive() {
		return active;
	}
}
