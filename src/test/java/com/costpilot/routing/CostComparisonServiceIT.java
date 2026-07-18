package com.costpilot.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.PriceVersioningService;

// 7.1 acceptance: given a request + allowed set, the ranked cheapest-first list
// matches hand-computed fixtures, and estimates use the price version active at
// request time (2.3), not the current one.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CostComparisonServiceIT {

	@Autowired
	private CostComparisonService comparison;

	@Autowired
	private PriceVersioningService versioning;

	// 30 chars -> chars/3 = 10 input tokens; max_tokens 100 output tokens
	private static CanonicalChatRequest request() {
		return new CanonicalChatRequest("gpt-4o-mini",
				List.of(new CanonicalChatRequest.Message("user", "012345678901234567890123456789")),
				100, false);
	}

	@Test
	void ranksAllowedModelsCheapestFirstAgainstHandComputedFixtures() {
		// hand-computed against the V2 seed prices (per-1k input/output), 10 in + 100 out:
		//   gpt-4o-mini       0.15/0.6   -> 0.0000015 + 0.00006  = 0.0000615
		//   gemini-2.5-flash  0.3/2.5    -> 0.000003  + 0.00025  = 0.000253
		//   claude-haiku-4-5  1/5        -> 0.00001   + 0.0005   = 0.00051
		//   gemini-2.5-pro    1.25/10    -> 0.0000125 + 0.001    = 0.0010125
		//   gpt-4o            2.5/10     -> 0.000025  + 0.001    = 0.001025
		//   claude-sonnet-4-5 3/15       -> 0.00003   + 0.0015   = 0.00153
		List<ModelCostCandidate> ranked = comparison.rank(request(),
				List.of("gpt-4o", "claude-sonnet-4-5", "gemini-2.5-pro",
						"gpt-4o-mini", "claude-haiku-4-5", "gemini-2.5-flash"),
				Instant.now());

		assertThat(ranked).extracting(ModelCostCandidate::model).containsExactly(
				"gpt-4o-mini", "gemini-2.5-flash", "claude-haiku-4-5",
				"gemini-2.5-pro", "gpt-4o", "claude-sonnet-4-5");

		assertThat(ranked.get(0).estimate().total()).isEqualByComparingTo("0.0000615");
		assertThat(ranked.get(1).estimate().total()).isEqualByComparingTo("0.000253");
		assertThat(ranked.get(2).estimate().total()).isEqualByComparingTo("0.00051");
		assertThat(ranked.get(3).estimate().total()).isEqualByComparingTo("0.0010125");
		assertThat(ranked.get(4).estimate().total()).isEqualByComparingTo("0.001025");
		assertThat(ranked.get(5).estimate().total()).isEqualByComparingTo("0.00153");

		assertThat(ranked.get(0).provider()).isEqualTo("openai");
		assertThat(ranked.get(1).provider()).isEqualTo("gemini");
		assertThat(ranked.get(2).provider()).isEqualTo("anthropic");
	}

	@Test
	void usesThePriceVersionActiveAtRequestTimeNotTheCurrentOne() throws Exception {
		String model = "gpt-compare-versioned";
		versioning.changePrice("openai", model,
				new BigDecimal("0.001000"), new BigDecimal("0.002000"));
		Instant underV1 = Instant.now();
		Thread.sleep(50); // version boundary strictly after underV1

		versioning.changePrice("openai", model,
				new BigDecimal("0.100000"), new BigDecimal("0.200000"));

		// at underV1 the v1 rates apply: 10 * 0.001/1k + 100 * 0.002/1k = 0.00021
		List<ModelCostCandidate> atV1 = comparison.rank(request(), List.of(model), underV1);
		assertThat(atV1).hasSize(1);
		assertThat(atV1.get(0).estimate().total()).isEqualByComparingTo("0.00021");

		// now the v2 rates apply: 10 * 0.1/1k + 100 * 0.2/1k = 0.021
		List<ModelCostCandidate> atNow = comparison.rank(request(), List.of(model), Instant.now());
		assertThat(atNow.get(0).estimate().total()).isEqualByComparingTo("0.021");
		assertThat(atNow.get(0).priceId()).isNotEqualTo(atV1.get(0).priceId());
	}

	@Test
	void unpricedModelsAreSkippedNotErrors() {
		List<ModelCostCandidate> ranked = comparison.rank(request(),
				List.of("gpt-4o-mini", "model-nobody-priced"), Instant.now());

		assertThat(ranked).extracting(ModelCostCandidate::model).containsExactly("gpt-4o-mini");
	}

	@Test
	void duplicatesCollapseAndTiesBreakDeterministically() {
		List<ModelCostCandidate> ranked = comparison.rank(request(),
				List.of("gpt-4o-mini", "gpt-4o-mini", "gpt-4o"), Instant.now());

		assertThat(ranked).extracting(ModelCostCandidate::model)
				.containsExactly("gpt-4o-mini", "gpt-4o");
	}
}
