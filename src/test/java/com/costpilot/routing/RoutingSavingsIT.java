package com.costpilot.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.budget.BudgetService;
import com.costpilot.core.model.Usage;
import com.costpilot.cost.CostService;
import com.costpilot.domain.UsageRecord;
import com.costpilot.domain.UsageRecordRepository;
import com.costpilot.security.AuthTestSupport;

// 7.3 acceptance: routing a request to a cheaper model records the counterfactual
// savings on the ledger row, and the accumulated ledger savings reconcile exactly with
// the per-request counterfactual recomputed from each row's own usage.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RoutingSavingsIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private UsageRecordRepository repository;

	@Autowired
	private CostService costService;

	@org.junit.jupiter.api.BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private static final String BODY = """
			{
			  "model": "%s",
			  "messages": [{"role": "user", "content": "hello costpilot savings"}],
			  "stream": false,
			  "max_tokens": 64
			}
			""";

	private ResponseEntity<String> post(String model, Integer minTier, String key) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Team-ID", "savings-team");
		headers.set("Idempotency-Key", key);
		if (minTier != null) {
			headers.set("X-CostPilot-Min-Tier", String.valueOf(minTier));
		}
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY.formatted(model), headers), String.class);
	}

	@Test
	void routedRequestRecordsPositiveSavingsThatReconcileWithTheLedger() throws Exception {
		// gpt-4o requested, tier>=1 bar -> routed to gpt-4o-mini (far cheaper); each such
		// request has a real positive counterfactual saving.
		for (int i = 0; i < 3; i++) {
			assertThat(post("gpt-4o", 1, "savings-" + UUID.randomUUID()).getStatusCode())
					.isEqualTo(HttpStatus.OK);
		}
		waitForRows(3);

		List<UsageRecord> rows = repository.findAll();
		assertThat(rows).hasSize(3);
		// every routed row carries a positive savings figure
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.getModel()).isEqualTo("gpt-4o-mini");
			assertThat(row.getSavingsNanos()).isNotNull().isPositive();
		});

		// reconcile: recompute what each row WOULD have cost on the requested gpt-4o,
		// minus its actual gpt-4o-mini cost, and sum. Must equal the ledger's stored total.
		long recomputedNanos = rows.stream()
				.mapToLong(row -> {
					Usage usage = new Usage(row.getInputTokens(), row.getOutputTokens());
					BigDecimal counterfactual = costService
							.costFor("openai", "gpt-4o", usage, row.getCreatedAt()).total();
					BigDecimal saving = counterfactual.subtract(row.getCost());
					return BudgetService.toNanos(saving.signum() > 0 ? saving : BigDecimal.ZERO);
				})
				.sum();
		assertThat(repository.totalSavingsNanos()).isEqualTo(recomputedNanos);
		assertThat(recomputedNanos).isPositive();
	}

	@Test
	void unroutedRequestRecordsNoSavings() throws Exception {
		// no min-tier bar -> no routing -> savings is null (unknown / not applicable),
		// distinct from a measured zero.
		assertThat(post("gpt-4o-mini", null, "no-route-" + UUID.randomUUID()).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		waitForRows(1);
		assertThat(repository.findAll().get(0).getSavingsNanos()).isNull();
	}

	private void waitForRows(long expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (repository.count() < expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(repository.count()).isGreaterThanOrEqualTo(expected);
	}
}
