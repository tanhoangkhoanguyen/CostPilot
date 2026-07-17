package com.costpilot.cost;

import java.util.concurrent.atomic.AtomicInteger;

import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.domain.ModelPrice;

/**
 * Running cost of one in-flight streamed response (4.2). Accrues token-by-token
 * as chunks arrive - each content delta counts as one token (the streaming
 * granularity of every provider we speak to) - and reconciles with the
 * provider-reported usage whenever a usage event rides along. The final value
 * therefore lands on exactly what the ledger bills. 4.3 reads runningCost()
 * mid-flight to cut off a response the instant it would breach budget.
 *
 * Pure in-memory arithmetic on the streaming path: no buffering, no I/O.
 */
public class StreamCostMeter {

	private final ModelPrice price;
	private final CostCalculator calculator;

	private final AtomicInteger inputTokens = new AtomicInteger();
	private final AtomicInteger reportedOutputTokens = new AtomicInteger();
	private final AtomicInteger countedOutputTokens = new AtomicInteger();

	StreamCostMeter(ModelPrice price, CostCalculator calculator) {
		this.price = price;
		this.calculator = calculator;
	}

	public void observe(CanonicalStreamChunk chunk) {
		if (chunk.contentDelta() != null && !chunk.contentDelta().isEmpty()) {
			countedOutputTokens.incrementAndGet();
		}
		if (chunk.usage() != null) {
			inputTokens.accumulateAndGet(chunk.usage().inputTokens(), Math::max);
			reportedOutputTokens.accumulateAndGet(chunk.usage().outputTokens(), Math::max);
		}
	}

	/** Provider-reported wins once present; the count keeps accruing before that. */
	public Usage usage() {
		return new Usage(inputTokens.get(),
				Math.max(reportedOutputTokens.get(), countedOutputTokens.get()));
	}

	public Cost runningCost() {
		return calculator.calculate(price, usage());
	}

	public ModelPrice price() {
		return price;
	}
}
