package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.Usage;

// Price lookup against the seeded (flyway V2) price table in a real Postgres.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CostServiceIT {

	@Autowired
	private CostService costService;

	@Test
	void costsSeededModelsFromTheDatabase() {
		Cost openai = costService.costFor("openai", "gpt-4o-mini", new Usage(2000, 1000), Instant.now());
		assertThat(openai.total()).isEqualByComparingTo("0.0009");

		Cost anthropic = costService.costFor("anthropic", "claude-sonnet-4-5", new Usage(1000, 2000), Instant.now());
		assertThat(anthropic.total()).isEqualByComparingTo("0.033");

		Cost gemini = costService.costFor("gemini", "gemini-2.5-flash", new Usage(10_000, 4000), Instant.now());
		assertThat(gemini.total()).isEqualByComparingTo("0.013");
	}

	@Test
	void unknownModelFailsWithClearError() {
		assertThatThrownBy(() -> costService.costFor("openai", "gpt-nonexistent", new Usage(1, 1), Instant.now()))
				.isInstanceOf(PriceNotFoundException.class)
				.hasMessageContaining("gpt-nonexistent");
	}
}
