package com.costpilot.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.Cost;
import com.costpilot.cost.CostEstimator;
import com.costpilot.cost.PriceLookupService;
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.domain.UsageRecordRepository;
import com.costpilot.policy.PolicyService;

// 4.1 acceptance: estimate within tolerance of actual on fixtures; an over-budget
// request is served on a cheaper policy-allowed model; original vs executed + reason recorded.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PreflightDowngradeIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private BudgetRepository budgets;

	@Autowired
	private UsageRecordRepository usageRepository;

	@Autowired
	private PolicyService policyService;

	@Autowired
	private CostEstimator estimator;

	@Autowired
	private PriceLookupService priceLookup;

	// 8 words, ~40 chars: the chars/3 input heuristic and word-count mock tokens
	// land close together, which is what the tolerance fixture needs
	private static final String CONTENT = "one two three four five six seven eight";

	private static final String BODY = """
			{
			  "model": "%s",
			  "messages": [{"role": "user", "content": "%s"}],
			  "stream": false,
			  "max_tokens": %d
			}
			""";

	private ResponseEntity<String> post(String team, String model, int maxTokens) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Team-ID", team);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY.formatted(model, CONTENT, maxTokens), headers), String.class);
	}

	@Test
	void estimateIsWithinToleranceOfActualOnAFixture() throws Exception {
		String team = "estimate-" + UUID.randomUUID();
		// max_tokens tuned to the mock's actual output ("[mock openai] " + 8 words = 10 tokens)
		ResponseEntity<String> response = post(team, "gpt-4o-mini", 10);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		Thread.sleep(300);
		var row = usageRepository.findAll().stream()
				.filter(r -> team.equals(r.getTeamId())).findFirst().orElseThrow();
		BigDecimal actual = row.getCost();

		Cost estimate = estimator.estimateMax(
				new CanonicalChatRequest("gpt-4o-mini",
						List.of(new CanonicalChatRequest.Message("user", CONTENT)), 10, false),
				priceLookup.priceAt("openai", "gpt-4o-mini", Instant.now()));

		// conservative by design: never under-estimates, and on this fixture stays
		// within 2x of the actual cost
		assertThat(estimate.total()).isGreaterThanOrEqualTo(actual);
		assertThat(estimate.total()).isLessThanOrEqualTo(actual.multiply(new BigDecimal(2)));
	}

	@Test
	void overBudgetRequestIsServedOnTheDowngradedModel(CapturedOutput output) throws Exception {
		String team = "downgrade-" + UUID.randomUUID();
		// gpt-4o estimate at max_tokens=256 is ~0.00256; gpt-4o-mini is ~0.000154.
		// cap 0.0005 blocks gpt-4o but fits mini
		budgets.save(new Budget("team", team, new BigDecimal("0.0005")));
		policyService.upsertRule("team", team, "gpt-4o,gpt-4o-mini", "deny", null);

		ResponseEntity<String> response = post(team, "gpt-4o", 256);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst("X-CostPilot-Model-Downgraded"))
				.isEqualTo("gpt-4o -> gpt-4o-mini; reason=budget");
		assertThat(response.getBody()).contains("\"model\":\"gpt-4o-mini\"");

		// audit: original vs executed + reason recorded
		assertThat(output.getOut()).contains(
				"auto-downgrade reason=budget original=gpt-4o executed=gpt-4o-mini");

		Thread.sleep(300);
		assertThat(usageRepository.findAll().stream()
				.filter(r -> team.equals(r.getTeamId()))
				.allMatch(r -> r.getModel().equals("gpt-4o-mini"))).isTrue();
	}

	@Test
	void downgradeNeverPicksAPolicyForbiddenModel() {
		String team = "downgrade-policy-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, new BigDecimal("0.0005")));
		// only gpt-4o is allowed - the cheaper models exist but policy forbids them
		policyService.upsertRule("team", team, "gpt-4o", "deny", null);

		ResponseEntity<String> response = post(team, "gpt-4o", 256);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
		assertThat(response.getBody()).contains("\"type\":\"budget_exceeded\"");
	}

	@Test
	void whenNothingFitsTheOriginal402Escapes() {
		String team = "nofit-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, new BigDecimal("0.00000001")));

		ResponseEntity<String> response = post(team, "gpt-4o", 256);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
	}
}
