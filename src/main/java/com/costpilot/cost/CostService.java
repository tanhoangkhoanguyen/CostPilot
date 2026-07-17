package com.costpilot.cost;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.core.model.Usage;
import com.costpilot.domain.ModelPrice;

@Service
public class CostService {

	private static final Logger log = LoggerFactory.getLogger(CostService.class);

	private final PriceLookupService priceLookup;
	private final CostCalculator calculator;

	public CostService(PriceLookupService priceLookup, CostCalculator calculator) {
		this.priceLookup = priceLookup;
		this.calculator = calculator;
	}

	/** Cost plus the exact price version that produced it. */
	public record Priced(ModelPrice price, Cost cost) {
	}

	public Priced pricedCostFor(String provider, String model, Usage usage, Instant at) {
		ModelPrice price = priceLookup.priceAt(provider, model, at);
		Cost cost = calculator.calculate(price, usage);
		log.info("cost provider={} model={} priceVersion={} inputTokens={} outputTokens={} cost={}",
				provider, model, price.getVersion(), usage.inputTokens(), usage.outputTokens(),
				cost.total().toPlainString());
		return new Priced(price, cost);
	}

	public Cost costFor(String provider, String model, Usage usage, Instant at) {
		return pricedCostFor(provider, model, usage, at).cost();
	}

	/** A per-request running meter pinned to the price version active at `at` (4.2). */
	public StreamCostMeter meter(String provider, String model, Instant at, int assumedInputTokens) {
		return new StreamCostMeter(priceLookup.priceAt(provider, model, at), calculator, assumedInputTokens);
	}
}
