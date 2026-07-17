package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import com.costpilot.domain.UsageRecord;
import com.costpilot.domain.UsageRecordRepository;

// End-to-end money spine: gateway request -> mock upstream -> usage -> cost -> ledger row.
// The ledger sum must equal the recomputed per-request costs exactly.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GatewayLedgerReconciliationIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private UsageRecordRepository repository;

	@Autowired
	private CostService costService;

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private static final String BODY = """
			{
			  "model": "gpt-4o-mini",
			  "messages": [{"role": "user", "content": "hello costpilot ledger"}],
			  "stream": false
			}
			""";

	private ResponseEntity<String> post(String body, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Team-ID", "team-a");
		headers.set("X-Project-ID", "project-x");
		if (idempotencyKey != null) {
			headers.set("Idempotency-Key", idempotencyKey);
		}
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	@Test
	void everyGatewayRequestLandsExactlyOneLedgerRowAndSumsReconcile() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(post(BODY, null).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		// ledger writes happen on the reactive pipeline; give them a beat
		waitForRows(3);

		List<UsageRecord> rows = repository.findAll();
		assertThat(rows).hasSize(3);
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.getTeamId()).isEqualTo("team-a");
			assertThat(row.getProjectId()).isEqualTo("project-x");
			assertThat(row.getProvider()).isEqualTo("openai");
			assertThat(row.getInputTokens()).isGreaterThan(0);
		});

		// recompute each row's cost from its recorded usage - sum must match exactly
		BigDecimal recomputed = rows.stream()
				.map(row -> costService.costFor(row.getProvider(), row.getModel(),
						new com.costpilot.core.model.Usage(row.getInputTokens(), row.getOutputTokens()),
						row.getCreatedAt()).total())
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(repository.totalCost()).isEqualByComparingTo(recomputed);
	}

	@Test
	void clientRetryWithSameIdempotencyKeyIsNotDoubleCounted() throws Exception {
		String key = "client-retry-1";
		assertThat(post(BODY, key).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(post(BODY, key).getStatusCode()).isEqualTo(HttpStatus.OK);
		waitForRows(1);
		Thread.sleep(500); // would a second row still show up? it must not
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void streamingRequestsAlsoLandInTheLedger() throws Exception {
		String streamBody = BODY.replace("\"stream\": false", "\"stream\": true");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
		headers.set("X-Team-ID", "team-a");
		ResponseEntity<String> response = restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(streamBody, headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		waitForRows(1);
		UsageRecord row = repository.findAll().get(0);
		assertThat(row.getOutputTokens()).isGreaterThan(0);
		assertThat(row.getCost()).isGreaterThan(BigDecimal.ZERO);
	}

	private void waitForRows(long expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (repository.count() < expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(repository.count()).isGreaterThanOrEqualTo(expected);
	}
}
