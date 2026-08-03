package com.costpilot.metering;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.costpilot.cost.CostCalculator;
import com.costpilot.pricing.PriceLookupService;

/**
 * Builds a per-request {@link StreamCostMeter} pinned to the price version active at a
 * given instant (4.2).
 *
 * <p>This used to be {@code CostService.meter(...)}. It lives here instead because
 * {@code cost} would otherwise have to import {@code metering} to hand one back, while
 * {@code metering} already imports {@code cost} for {@link CostCalculator} and
 * {@code Cost} - a package cycle, which {@code ./gradlew packageCycles} now rejects.
 * The factory belongs next to the thing it builds; the dependency then runs one way,
 * metering -> cost + pricing.
 *
 * <p>Behaviour is byte-for-byte what {@code CostService.meter} did.
 */
@Service
public class StreamCostMeterFactory {

	private final PriceLookupService priceLookup;
	private final CostCalculator calculator;

	public StreamCostMeterFactory(PriceLookupService priceLookup, CostCalculator calculator) {
		this.priceLookup = priceLookup;
		this.calculator = calculator;
	}

	public StreamCostMeter meter(String provider, String model, Instant at, int assumedInputTokens) {
		return new StreamCostMeter(priceLookup.priceAt(provider, model, at), calculator, assumedInputTokens);
	}
}
