package com.costpilot.cost;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.costpilot.core.model.Usage;
import com.costpilot.domain.ModelPrice;

// cost = input_tokens x in_rate/1k + output_tokens x out_rate/1k, in BigDecimal
// end to end - token counts and per-1k rates multiply exactly, division by 1000
// only shifts the decimal point, so no rounding ever happens here.
@Component
public class CostCalculator {

	private static final BigDecimal PER_TOKENS = new BigDecimal(1000);

	public Cost calculate(ModelPrice price, Usage usage) {
		return new Cost(
				side(usage.inputTokens(), price.getInputPricePer1k()),
				side(usage.outputTokens(), price.getOutputPricePer1k()));
	}

	private BigDecimal side(int tokens, BigDecimal ratePer1k) {
		// scale grows by 3 at most (divide by 10^3), never a repeating decimal
		return ratePer1k.multiply(BigDecimal.valueOf(tokens))
				.divide(PER_TOKENS);
	}
}
