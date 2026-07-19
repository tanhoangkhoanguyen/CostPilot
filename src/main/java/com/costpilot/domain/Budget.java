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
@Table(name = "budget")
public class Budget {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "scope_type", nullable = false, columnDefinition = "text")
	private String scopeType;

	@Column(name = "scope_ref", nullable = false, columnDefinition = "text")
	private String scopeRef;

	@Column(name = "limit_amount", nullable = false, precision = 18, scale = 9)
	private BigDecimal limitAmount;

	@Column(nullable = false, columnDefinition = "text")
	private String currency = "USD";

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected Budget() {
	}

	public Budget(String scopeType, String scopeRef, BigDecimal limitAmount) {
		this.scopeType = scopeType;
		this.scopeRef = scopeRef;
		this.limitAmount = limitAmount;
	}

	// 9.1: admin CRUD mutators. Changing the limit or (de)activating a budget takes
	// effect at runtime once the caller refreshes the Redis counter (BudgetService).
	public void setLimitAmount(BigDecimal limitAmount) {
		this.limitAmount = limitAmount;
	}

	public void setActive(boolean active) {
		this.active = active;
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

	public BigDecimal getLimitAmount() {
		return limitAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
