package com.costpilot.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 7.2: a model's quality tier (1 = economy, 2 = standard, 3 = frontier).
@Entity
@Table(name = "model_capability")
public class ModelCapability {

	@Id
	@Column(columnDefinition = "text")
	private String model;

	@Column(nullable = false)
	private int tier;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected ModelCapability() {
	}

	public ModelCapability(String model, int tier) {
		this.model = model;
		this.tier = tier;
	}

	public String getModel() {
		return model;
	}

	public int getTier() {
		return tier;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
