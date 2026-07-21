package com.costpilot.routing;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.costpilot.domain.AuditRecord;
import com.costpilot.domain.AuditRecordRepository;
import com.costpilot.policy.PolicyService;
import com.costpilot.security.AuthTestSupport;

// 7.2 acceptance: a low-bar request routes to a cheap model; a high-bar request is
// never downgraded below its bar; the routing decision + reason land in the audit
// trail (5.1) - and from there in the usage event (5.2), which serializes
// audit.getDecision() verbatim.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RoutingPolicyIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AuditRecordRepository auditRepository;

	@Autowired
	private PolicyService policyService;

	private static final String BODY = """
			{
			  "model": "%s",
			  "messages": [{"role": "user", "content": "hello costpilot"}],
			  "stream": false,
			  "max_tokens": 64
			}
			""";

	private ResponseEntity<String> post(String team, String model, Integer minTier, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Team-ID", team);
		headers.set("Idempotency-Key", idempotencyKey);
		if (minTier != null) {
			headers.set("X-CostPilot-Min-Tier", String.valueOf(minTier));
		}
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY.formatted(model), headers), String.class);
	}

	private AuditRecord auditFor(String idempotencyKey) {
		return auditRepository.findAll().stream()
				.filter(a -> idempotencyKey.equals(a.getIdempotencyKey()))
				.findFirst().orElseThrow();
	}

	@Test
	void lowBarRequestRoutesToTheCheapestQualifyingModel() {
		String team = "route-low-" + UUID.randomUUID();
		String key = "route-low-" + UUID.randomUUID();

		// gpt-4o requested, but bar is tier>=1: cheapest tier-1 model is gpt-4o-mini
		ResponseEntity<String> response = post(team, "gpt-4o", 1, key);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst("X-CostPilot-Model-Routed"))
				.contains("gpt-4o -> gpt-4o-mini")
				.contains("tier>=1");
		assertThat(response.getBody()).contains("\"model\":\"gpt-4o-mini\"");

		AuditRecord audit = auditFor(key);
		assertThat(audit.getDecision()).isEqualTo("route");
		assertThat(audit.getRequestedModel()).isEqualTo("gpt-4o");
		assertThat(audit.getExecutedModel()).isEqualTo("gpt-4o-mini");
		assertThat(audit.getReason()).contains("cheapest model with tier>=1");
	}

	@Test
	void highBarRequestIsNeverRoutedBelowItsBar() {
		String team = "route-high-" + UUID.randomUUID();
		String key = "route-high-" + UUID.randomUUID();

		// bar tier>=3: candidates are gemini-2.5-pro (0.0010125), gpt-4o (0.001025),
		// claude-sonnet-4-5 (0.00153) for this request - cheapest FRONTIER model wins,
		// never a tier-1/2 model even though those are far cheaper
		ResponseEntity<String> response = post(team, "gpt-4o-mini", 3, key);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		AuditRecord audit = auditFor(key);
		assertThat(audit.getExecutedModel()).isEqualTo("gemini-2.5-pro");
		assertThat(audit.getDecision()).isEqualTo("route");
		assertThat(List.of("gpt-4o", "claude-sonnet-4-5", "gemini-2.5-pro"))
				.contains(audit.getExecutedModel());
	}

	@Test
	void withoutADeclaredBarNothingIsRouted() {
		String team = "route-none-" + UUID.randomUUID();
		String key = "route-none-" + UUID.randomUUID();

		ResponseEntity<String> response = post(team, "gpt-4o", null, key);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst("X-CostPilot-Model-Routed")).isNull();
		AuditRecord audit = auditFor(key);
		assertThat(audit.getDecision()).isEqualTo("allow");
		assertThat(audit.getExecutedModel()).isEqualTo("gpt-4o");
	}

	@Test
	void routingOnlyConsidersPolicyAllowedModels() {
		String team = "route-policy-" + UUID.randomUUID();
		String key = "route-policy-" + UUID.randomUUID();
		// policy: this team may only use gemini models; fallback deny
		policyService.upsertRule("team", team, "gemini-*", "deny", null);

		// bar tier>=1 -> cheapest qualifying among policy-allowed is gemini-2.5-flash,
		// NOT the globally-cheapest gpt-4o-mini
		ResponseEntity<String> response = post(team, "gemini-2.5-pro", 1, key);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		AuditRecord audit = auditFor(key);
		assertThat(audit.getExecutedModel()).isEqualTo("gemini-2.5-flash");
		assertThat(audit.getDecision()).isEqualTo("route");
	}

	@Autowired
	private com.costpilot.budget.DowngradeService downgradeService;

	@Test
	void budgetDowngradeCandidatesNeverFallBelowTheDeclaredBar() {
		// "hello costpilot" = 15 chars -> 5 input tokens; 64 output tokens. Cheaper
		// alternatives to claude-sonnet-4-5 now start at gemini-2.5-flash-lite (the
		// cheapest priced model, 11.3) - budget downgrade ignores tier, so it lands on
		// the cheapest allowed model. With a tier>=3 bar only the frontier models cheaper
		// than sonnet survive: gemini-2.5-pro (0.00064625) then gpt-4o (0.0006525);
		// flash-lite has no capability tier, so tier-routing never considers it.
		com.costpilot.core.model.CanonicalChatRequest request = new com.costpilot.core.model.CanonicalChatRequest(
				"claude-sonnet-4-5",
				List.of(new com.costpilot.core.model.CanonicalChatRequest.Message("user", "hello costpilot")),
				64, false);
		com.costpilot.cost.LedgerContext context = new com.costpilot.cost.LedgerContext(
				null, "bar-" + UUID.randomUUID(), null, null, null, UUID.randomUUID().toString());

		var withBar = downgradeService.cheaperAllowedAlternatives(request, context, 3);
		assertThat(withBar).extracting(com.costpilot.budget.DowngradeService.Candidate::model)
				.containsExactly("gemini-2.5-pro", "gpt-4o");

		var withoutBar = downgradeService.cheaperAllowedAlternatives(request, context);
		assertThat(withoutBar.get(0).model()).isEqualTo("gemini-2.5-flash-lite");
	}

	@Test
	void whenTheCheapestQualifierIsTheRequestedModelNothingChanges() {
		String team = "route-same-" + UUID.randomUUID();
		String key = "route-same-" + UUID.randomUUID();

		// gpt-4o-mini already IS the cheapest tier>=1 model - no swap, plain allow
		ResponseEntity<String> response = post(team, "gpt-4o-mini", 1, key);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst("X-CostPilot-Model-Routed")).isNull();
		assertThat(auditFor(key).getDecision()).isEqualTo("allow");
	}
}
