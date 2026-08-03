package com.costpilot.metering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.cost.Cost;
import com.costpilot.cost.CostCalculator;
import com.costpilot.pricing.ModelPrice;

class StreamCostMeterTest {

	private final ModelPrice price = new ModelPrice("openai", "gpt-4o-mini",
			new BigDecimal("0.000150"), new BigDecimal("0.000600"));
	private final CostCalculator calculator = new CostCalculator();

	private StreamCostMeter meter() {
		return new StreamCostMeter(price, calculator, 0);
	}

	@Test
	void accruesByContentLengthAsChunksArrive() {
		StreamCostMeter meter = meter();
		assertThat(meter.runningCost().total()).isEqualByComparingTo("0");

		meter.observe(CanonicalStreamChunk.role("assistant"));
		assertThat(meter.usage().outputTokens()).isZero(); // role delta has no content

		BigDecimal previous = BigDecimal.ZERO;
		for (int i = 1; i <= 5; i++) {
			meter.observe(CanonicalStreamChunk.content("tok" + i + " "));
			BigDecimal running = meter.runningCost().total();
			assertThat(running).isGreaterThan(previous); // strictly monotonic accrual
			previous = running;
		}
		// 5 chunks x 5 chars = 25 chars -> ceil(25/4) = 7 estimated tokens x 0.0006/1k
		assertThat(meter.usage().outputTokens()).isEqualTo(7);
		assertThat(meter.runningCost().total()).isEqualByComparingTo("0.0000042");
	}

	@Test
	void estimateTracksBigMultiTokenChunksSoCutoffCanFire() {
		// a Gemini-style chunk carrying many tokens of text in one delta: the
		// length-based estimate must reflect it (one-per-chunk would under-count ~40x)
		StreamCostMeter meter = meter();
		String bigDelta = "word ".repeat(40); // 200 chars ~= 50 tokens
		meter.observe(CanonicalStreamChunk.content(bigDelta));
		assertThat(meter.usage().outputTokens()).isEqualTo(50); // ceil(200/4), not 1
	}

	@Test
	void cjkOutputEstimateReflectsDenserTokenizationNotFlatCharsPerFour() {
		// 6 Han ideographs. A flat chars/4 would estimate ceil(6/4)=2 tokens and the
		// cutoff would lag ~3x; script weighting estimates ~1 token/char = 6.
		StreamCostMeter meter = meter();
		meter.observe(CanonicalStreamChunk.content("你好世界世界"));
		assertThat(meter.usage().outputTokens()).isEqualTo(6);
	}

	@Test
	void diacriticLatinAccruesFasterThanAsciiOfTheSameLength() {
		StreamCostMeter ascii = meter();
		ascii.observe(CanonicalStreamChunk.content("abcdefgh")); // 8 ASCII -> 2 tokens

		StreamCostMeter toned = meter();
		toned.observe(CanonicalStreamChunk.content("áàảãạ".repeat(2))); // 10 diacritic chars

		assertThat(toned.usage().outputTokens())
				.isGreaterThan(ascii.usage().outputTokens());
	}

	@Test
	void providerReportedOutputSupersedesAHigherEstimate() {
		// the length estimate can run ahead of the provider's real count; once the
		// authoritative usage arrives it wins for the ledger, never Math.max (#89)
		StreamCostMeter meter = meter();
		meter.observe(CanonicalStreamChunk.content("你好世界世界")); // estimate = 6
		meter.observe(CanonicalStreamChunk.usageOnly(new Usage(20, 3)));
		assertThat(meter.usage().outputTokens()).isEqualTo(3);
	}

	@Test
	void reportedUsageReconcilesInputAndOutput() {
		StreamCostMeter meter = meter();
		meter.observe(CanonicalStreamChunk.content("a "));
		meter.observe(CanonicalStreamChunk.content("b "));
		// provider reports the authoritative totals at stream end
		meter.observe(CanonicalStreamChunk.usageOnly(new Usage(10, 2)));

		assertThat(meter.usage().inputTokens()).isEqualTo(10);
		assertThat(meter.usage().outputTokens()).isEqualTo(2);
		// (10 x 0.00015 + 2 x 0.0006) / 1000
		assertThat(meter.runningCost().total()).isEqualByComparingTo("0.0000027");
	}

	@Test
	void finalValueEqualsWhatTheCalculatorBillsForTheSameUsage() {
		StreamCostMeter meter = meter();
		meter.observe(CanonicalStreamChunk.usageOnly(new Usage(100, 50)));
		Cost direct = calculator.calculate(price, new Usage(100, 50));
		assertThat(meter.runningCost().total()).isEqualByComparingTo(direct.total());
	}
}
