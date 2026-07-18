package com.costpilot.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.security.AuthTestSupport;
import com.costpilot.domain.AuditRecord;
import com.costpilot.domain.AuditRecordRepository;
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.policy.PolicyService;

// 5.1 acceptance: every governed request produces a queryable audit row, and a
// downgrade or cutoff is fully explained (original-vs-executed + why). Drives the real
// gateway end-to-end for each decision type and asserts the persisted audit row.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "costpilot.mock-upstream.token-delay-ms=1")
@Import(TestcontainersConfiguration.class)
class AuditTrailIT {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AuditRecordRepository audit;

	@Autowired
	private PolicyService policyService;

	@Autowired
	private BudgetRepository budgets;

	private static final String BODY = """
			{
			  "model": "%s",
			  "messages": [{"role": "user", "content": "one two three four five six seven eight"}],
			  "stream": false,
			  "max_tokens": %d
			}
			""";

	private ResponseEntity<String> post(String team, String model, int maxTokens) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		headers.set("X-Team-ID", team);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY.formatted(model, maxTokens), headers), String.class);
	}

	// the audit write for a forwarded request settles asynchronously (doFinally on the
	// stream path, doOnNext on the non-streaming Mono); poll until the team's row appears
	private AuditRecord awaitRow(String team) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		AuditRecord row = null;
		while (System.currentTimeMillis() < deadline && row == null) {
			row = audit.findAll().stream().filter(a -> team.equals(a.getTeamId())).findFirst().orElse(null);
			if (row == null) {
				Thread.sleep(100);
			}
		}
		assertThat(row).as("audit row for team %s", team).isNotNull();
		return row;
	}

	@Test
	void allowProducesAnAuditRowWithIdenticalRequestedAndExecuted() throws Exception {
		String team = "audit-allow-" + UUID.randomUUID();

		assertThat(post(team, "gpt-4o-mini", 16).getStatusCode()).isEqualTo(HttpStatus.OK);

		AuditRecord row = awaitRow(team);
		assertThat(row.getDecision()).isEqualTo("allow");
		assertThat(row.getRequestedModel()).isEqualTo("gpt-4o-mini");
		assertThat(row.getExecutedModel()).isEqualTo("gpt-4o-mini");
		assertThat(row.getFinishReason()).isEqualTo("stop");
		assertThat(row.getCost()).isNotNull();
		assertThat(row.getUsageRecordId()).isNotNull();
	}

	@Test
	void policyDowngradeIsFullyExplained() throws Exception {
		String team = "audit-poldown-" + UUID.randomUUID();
		var rule = policyService.upsertRule("team", team, "gpt-4o-mini", "downgrade", "gpt-4o-mini");

		assertThat(post(team, "gpt-4o", 16).getStatusCode()).isEqualTo(HttpStatus.OK);

		AuditRecord row = awaitRow(team);
		assertThat(row.getDecision()).isEqualTo("downgrade");
		assertThat(row.getRequestedModel()).isEqualTo("gpt-4o");
		assertThat(row.getExecutedModel()).isEqualTo("gpt-4o-mini");
		assertThat(row.getReason()).isEqualTo("policy");
		assertThat(row.getMatchedRuleId()).isEqualTo(rule.getId());
	}

	@Test
	void budgetDowngradeIsFullyExplained() throws Exception {
		String team = "audit-budown-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, new BigDecimal("0.0005")));
		policyService.upsertRule("team", team, "gpt-4o,gpt-4o-mini", "deny", null);

		assertThat(post(team, "gpt-4o", 256).getStatusCode()).isEqualTo(HttpStatus.OK);

		AuditRecord row = awaitRow(team);
		assertThat(row.getDecision()).isEqualTo("downgrade");
		assertThat(row.getRequestedModel()).isEqualTo("gpt-4o");
		assertThat(row.getExecutedModel()).isEqualTo("gpt-4o-mini");
		assertThat(row.getReason()).isEqualTo("budget");
		assertThat(row.getBlockedScope()).isEqualTo("team");
	}

	@Test
	void denyProducesARejectedAuditRow() throws Exception {
		String team = "audit-deny-" + UUID.randomUUID();
		policyService.upsertRule("team", team, "gpt-4o-mini", "deny", null);

		assertThat(post(team, "claude-sonnet-4-5", 16).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		AuditRecord row = awaitRow(team);
		assertThat(row.getDecision()).isEqualTo("deny");
		assertThat(row.getRequestedModel()).isEqualTo("claude-sonnet-4-5");
		assertThat(row.getExecutedModel()).isNull();
		assertThat(row.getCost()).isNull();
	}

	@Test
	void requireApprovalProducesARejectedAuditRow() throws Exception {
		String team = "audit-appr-" + UUID.randomUUID();
		policyService.upsertRule("team", team, "gpt-4o-mini", "require_approval", null);

		assertThat(post(team, "gpt-4o", 16).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		AuditRecord row = awaitRow(team);
		assertThat(row.getDecision()).isEqualTo("require_approval");
		assertThat(row.getExecutedModel()).isNull();
	}

	@Test
	void budgetHardBlockThatEscapesAs402IsAudited() throws Exception {
		String team = "audit-402-" + UUID.randomUUID();
		// cap so tiny nothing fits -> the original 402 escapes (PreflightDowngradeIT scenario)
		budgets.save(new Budget("team", team, new BigDecimal("0.00000001")));

		assertThat(post(team, "gpt-4o", 256).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);

		AuditRecord row = awaitRow(team);
		assertThat(row.getDecision()).isEqualTo("deny");
		assertThat(row.getReason()).isEqualTo("budget");
		assertThat(row.getBlockedScope()).isEqualTo("team");
		assertThat(row.getExecutedModel()).isNull();
	}

	@Test
	void midStreamCutoffIsRecordedWithBudgetCutoffFinishReason() throws Exception {
		String team = "audit-cutoff-" + UUID.randomUUID();
		// mirror MidStreamCutoffIT: a cap that the ~2000-token stream overruns mid-flight,
		// no max_tokens so the reservation under-estimates and cutoff (4.3) fires
		budgets.save(new Budget("team", team, new BigDecimal("0.0013")));
		String content = "lorem ".repeat(2000).trim();
		String body = """
				{
				  "model": "gpt-4o-mini",
				  "messages": [{"role": "user", "content": "%s"}],
				  "stream": true
				}
				""".formatted(content);

		WebClient.create("http://localhost:" + port).post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.header("X-Team-ID", team)
				.header("Authorization", "Bearer " + AuthTestSupport.ADMIN_KEY)
				.bodyValue(body)
				.retrieve()
				.bodyToFlux(String.class)
				.collectList()
				.block(Duration.ofSeconds(60));

		AuditRecord row = awaitRow(team);
		assertThat(row.getFinishReason()).isEqualTo("budget_cutoff");
		assertThat(row.getExecutedModel()).isEqualTo("gpt-4o-mini");
		assertThat(row.getCost()).isNotNull();
	}
}
