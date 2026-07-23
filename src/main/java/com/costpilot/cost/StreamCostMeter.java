package com.costpilot.cost;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.domain.ModelPrice;

/**
 * Running cost of one in-flight streamed response (4.2). Accrues cost as chunks
 * arrive by estimating output tokens from the cumulative length of content
 * deltas, and reconciles with the provider-reported usage whenever a usage event
 * rides along. The final value therefore lands on exactly what the ledger bills.
 * 4.3 reads runningCost() mid-flight to cut off a response the instant it would
 * breach budget.
 *
 * <p><b>Why length-based, not one-token-per-chunk (12.1):</b> providers chunk
 * their SSE stream very differently. The mock and OpenAI-style streams emit one
 * token per chunk, but Gemini packs dozens of tokens into each chunk and reports
 * authoritative usage only in the FINAL chunk. A one-token-per-chunk counter
 * therefore under-counts a Gemini stream by ~40x mid-flight, so the 4.3 cutoff
 * check never trips and the whole response is billed (overshoot unbounded). A
 * ~4-chars-per-token estimate (the widely-used rule of thumb) tracks real
 * generation closely enough on every provider for the cutoff to fire on time;
 * the provider's exact count still wins for the ledger the instant it arrives.
 *
 * <p>Pure in-memory arithmetic on the streaming path: no buffering, no I/O.
 */
public class StreamCostMeter {

	private final ModelPrice price;
	private final CostCalculator calculator;
	/**
	 * Pre-flight input estimate: most providers report usage only at stream END,
	 * so mid-flight the input side would otherwise cost zero and a 4.3 cutoff
	 * would fire too late. The estimate stands in (conservative) until the
	 * provider reports; a cut-off stream that never got a usage event is billed
	 * with the estimated input - documented behavior of partial records.
	 */
	private final int assumedInputTokens;

	/** ~4 characters per token - the standard rough tokenizer heuristic. */
	private static final int CHARS_PER_TOKEN = 4;

	private final AtomicInteger reportedInputTokens = new AtomicInteger();
	private final AtomicInteger reportedOutputTokens = new AtomicInteger();
	private final AtomicLong contentChars = new AtomicLong();

	StreamCostMeter(ModelPrice price, CostCalculator calculator, int assumedInputTokens) {
		this.price = price;
		this.calculator = calculator;
		this.assumedInputTokens = assumedInputTokens;
	}

	public void observe(CanonicalStreamChunk chunk) {
		if (chunk.contentDelta() != null && !chunk.contentDelta().isEmpty()) {
			contentChars.addAndGet(chunk.contentDelta().length());
		}
		if (chunk.usage() != null) {
			reportedInputTokens.accumulateAndGet(chunk.usage().inputTokens(), Math::max);
			reportedOutputTokens.accumulateAndGet(chunk.usage().outputTokens(), Math::max);
		}
	}

	/**
	 * Provider-reported output wins once present; before that, a length-based
	 * estimate (ceil chars/4) stands in so the mid-stream cutoff sees a realistic
	 * running cost on providers that chunk many tokens at a time (12.1).
	 */
	public Usage usage() {
		int input = reportedInputTokens.get() > 0 ? reportedInputTokens.get() : assumedInputTokens;
		int estimatedOutput = (int) ((contentChars.get() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
		return new Usage(input, Math.max(reportedOutputTokens.get(), estimatedOutput));
	}

	public Cost runningCost() {
		return calculator.calculate(price, usage());
	}

	public ModelPrice price() {
		return price;
	}
}
