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
@Table(name = "model_price")
public class ModelPrice {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, columnDefinition = "text")
	private String provider;

	@Column(nullable = false, columnDefinition = "text")
	private String model;

	@Column(nullable = false, columnDefinition = "text")
	private String currency = "USD";

	// money math is BigDecimal end to end (2.1)
	@Column(name = "input_price_per_1k", nullable = false, precision = 12, scale = 6)
	private BigDecimal inputPricePer1k;

	@Column(name = "output_price_per_1k", nullable = false, precision = 12, scale = 6)
	private BigDecimal outputPricePer1k;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected ModelPrice() {
	}

	public ModelPrice(String provider, String model, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {
		this.provider = provider;
		this.model = model;
		this.inputPricePer1k = inputPricePer1k;
		this.outputPricePer1k = outputPricePer1k;
	}

	public UUID getId() {
		return id;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getInputPricePer1k() {
		return inputPricePer1k;
	}

	public BigDecimal getOutputPricePer1k() {
		return outputPricePer1k;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
