package com.costpilot.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 9.1: an admin control-plane action - who changed what governance config, when.
 * Distinct from {@link AuditRecord}, which explains per-request spending decisions.
 */
@Entity
@Table(name = "admin_audit")
public class AdminAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, columnDefinition = "text")
	private String actor;

	@Column(nullable = false, columnDefinition = "text")
	private String action;

	@Column(name = "target_type", nullable = false, columnDefinition = "text")
	private String targetType;

	@Column(name = "target_ref", nullable = false, columnDefinition = "text")
	private String targetRef;

	@Column(name = "old_value", columnDefinition = "text")
	private String oldValue;

	@Column(name = "new_value", columnDefinition = "text")
	private String newValue;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected AdminAudit() {
	}

	public AdminAudit(String actor, String action, String targetType, String targetRef,
			String oldValue, String newValue) {
		this.actor = actor;
		this.action = action;
		this.targetType = targetType;
		this.targetRef = targetRef;
		this.oldValue = oldValue;
		this.newValue = newValue;
	}

	public UUID getId() {
		return id;
	}

	public String getActor() {
		return actor;
	}

	public String getAction() {
		return action;
	}

	public String getTargetType() {
		return targetType;
	}

	public String getTargetRef() {
		return targetRef;
	}

	public String getOldValue() {
		return oldValue;
	}

	public String getNewValue() {
		return newValue;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
