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

@Entity
@Table(name = "usage_record")
public class UsageRecord {

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

	@Column(nullable = false, columnDefinition = "text")
	private String provider;

	@Column(nullable = false, columnDefinition = "text")
	private String model;

	@Column(name = "input_tokens", nullable = false)
	private int inputTokens;

	@Column(name = "output_tokens", nullable = false)
	private int outputTokens;

	@Column(nullable = false, precision = 18, scale = 9)
	private BigDecimal cost;

	// exact model_price row (version) that priced this request - historical costs
	// stay reproducible after any price change (2.3)
	@Column(name = "price_id")
	private UUID priceId;

	@Column(name = "idempotency_key", nullable = false, unique = true, columnDefinition = "text")
	private String idempotencyKey;

	// 7.3: exact integer nanodollars saved by routing/downgrading this request vs its
	// requested model. Null when no routing happened or the requested model was unpriced
	// (savings unknown) - distinct from a measured zero.
	@Column(name = "savings_nanos")
	private Long savingsNanos;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected UsageRecord() {
	}

	public UsageRecord(String tenantId, String teamId, String projectId, String userId, String environment,
			String provider, String model, int inputTokens, int outputTokens, BigDecimal cost,
			UUID priceId, String idempotencyKey) {
		this.tenantId = tenantId;
		this.teamId = teamId;
		this.projectId = projectId;
		this.userId = userId;
		this.environment = environment;
		this.provider = provider;
		this.model = model;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.cost = cost;
		this.priceId = priceId;
		this.idempotencyKey = idempotencyKey;
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

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public int getInputTokens() {
		return inputTokens;
	}

	public int getOutputTokens() {
		return outputTokens;
	}

	public BigDecimal getCost() {
		return cost;
	}

	public UUID getPriceId() {
		return priceId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public Long getSavingsNanos() {
		return savingsNanos;
	}

	public void setSavingsNanos(Long savingsNanos) {
		this.savingsNanos = savingsNanos;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
