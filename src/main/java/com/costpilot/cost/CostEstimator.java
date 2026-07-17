package com.costpilot.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.domain.ModelPrice;

// Conservative pre-flight MAX cost - what the guard reserves before forwarding.
// Deliberately over-estimates (chars/3 input heuristic, full max_tokens output)
// so a reservation always covers the actual charge and a flood can't overspend.
// 4.1 refines estimation accuracy and adds auto-downgrade on top of this.
@Component
public class CostEstimator {

	static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;
	private static final BigDecimal PER_TOKENS = new BigDecimal(1000);
	private static final BigDecimal CHARS_PER_TOKEN = new BigDecimal(3);

	public Cost estimateMax(CanonicalChatRequest request, ModelPrice price) {
		BigDecimal inputTokens = BigDecimal.valueOf(estimateInputTokens(request));
		BigDecimal outputTokens = BigDecimal.valueOf(
				request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_OUTPUT_TOKENS);

		return new Cost(
				price.getInputPricePer1k().multiply(inputTokens).divide(PER_TOKENS),
				price.getOutputPricePer1k().multiply(outputTokens).divide(PER_TOKENS));
	}

	/** chars/3 heuristic - also seeds the stream meter's assumed input (4.2/4.3). */
	public int estimateInputTokens(CanonicalChatRequest request) {
		long chars = request.messages().stream()
				.mapToLong(m -> m.content() == null ? 0 : m.content().length())
				.sum();
		return new BigDecimal(chars).divide(CHARS_PER_TOKEN, 0, RoundingMode.UP).intValueExact();
	}
}
