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

	public Cost costFor(String provider, String model, Usage usage, Instant at) {
		ModelPrice price = priceLookup.priceAt(provider, model, at);
		Cost cost = calculator.calculate(price, usage);
		log.info("cost provider={} model={} inputTokens={} outputTokens={} cost={}",
				provider, model, usage.inputTokens(), usage.outputTokens(), cost.total().toPlainString());
		return cost;
	}
}
