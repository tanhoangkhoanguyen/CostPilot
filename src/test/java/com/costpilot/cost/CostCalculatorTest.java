package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.costpilot.core.model.Usage;
import com.costpilot.domain.ModelPrice;

// Hand-calculated fixtures per provider/model (rates match V2 seed data).
class CostCalculatorTest {

	private final CostCalculator calculator = new CostCalculator();

	private ModelPrice price(String provider, String model, String in, String out) {
		return new ModelPrice(provider, model, new BigDecimal(in), new BigDecimal(out));
	}

	@Test
	void openAiCostMatchesHandCalculation() {
		// 2000 in x 0.00015/1k = 0.0003 ; 1000 out x 0.0006/1k = 0.0006 ; total 0.0009
		Cost cost = calculator.calculate(
				price("openai", "gpt-4o-mini", "0.000150", "0.000600"),
				new Usage(2000, 1000));

		assertThat(cost.inputCost()).isEqualByComparingTo("0.0003");
		assertThat(cost.outputCost()).isEqualByComparingTo("0.0006");
		assertThat(cost.total()).isEqualByComparingTo("0.0009");
	}

	@Test
	void anthropicCostMatchesHandCalculation() {
		// 1000 in x 0.003/1k = 0.003 ; 2000 out x 0.015/1k = 0.03 ; total 0.033
		Cost cost = calculator.calculate(
				price("anthropic", "claude-sonnet-4-5", "0.003000", "0.015000"),
				new Usage(1000, 2000));

		assertThat(cost.inputCost()).isEqualByComparingTo("0.003");
		assertThat(cost.outputCost()).isEqualByComparingTo("0.03");
		assertThat(cost.total()).isEqualByComparingTo("0.033");
	}

	@Test
	void geminiCostMatchesHandCalculation() {
		// 10000 in x 0.0003/1k = 0.003 ; 4000 out x 0.0025/1k = 0.01 ; total 0.013
		Cost cost = calculator.calculate(
				price("gemini", "gemini-2.5-flash", "0.000300", "0.002500"),
				new Usage(10_000, 4000));

		assertThat(cost.total()).isEqualByComparingTo("0.013");
	}

	@Test
	void oddTokenCountsStayExactNoFloatingPointDrift() {
		// 333 x 0.00015/1k = 0.00004995 - exact, would be dirty in double math
		Cost cost = calculator.calculate(
				price("openai", "gpt-4o-mini", "0.000150", "0.000600"),
				new Usage(333, 0));

		assertThat(cost.inputCost()).isEqualByComparingTo("0.00004995");
		assertThat(cost.outputCost()).isEqualByComparingTo("0");
	}

	@Test
	void zeroUsageCostsZero() {
		Cost cost = calculator.calculate(
				price("openai", "gpt-4o", "0.002500", "0.010000"),
				new Usage(0, 0));
		assertThat(cost.total()).isEqualByComparingTo("0");
	}
}
