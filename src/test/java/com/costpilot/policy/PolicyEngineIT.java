package com.costpilot.policy;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.costpilot.domain.UsageRecordRepository;

// 3.3 acceptance: rule changes apply without redeploy, every decision is logged
// with the matched rule, deny -> 403 with reason, downgrade swaps the executed model.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PolicyEngineIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private PolicyService policyService;

	@Autowired
	private UsageRecordRepository usageRepository;

	private static final String BODY = """
			{
			  "model": "%s",
			  "messages": [{"role": "user", "content": "hello costpilot"}],
			  "stream": false
			}
			""";

	private ResponseEntity<String> post(String team, String project, String model) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (team != null) {
			headers.set("X-Team-ID", team);
		}
		if (project != null) {
			headers.set("X-Project-ID", project);
		}
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY.formatted(model), headers), String.class);
	}

	@Test
	void deniedModelGets403WithReasonAndMatchedRule(CapturedOutput output) {
		String team = "policy-team-" + UUID.randomUUID();
		var rule = policyService.upsertRule("team", team, "gpt-4o-mini", "deny", null);

		ResponseEntity<String> denied = post(team, null, "claude-sonnet-4-5");
		assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(denied.getBody()).contains("\"type\":\"policy_denied\"");
		assertThat(denied.getBody()).contains(rule.getId().toString());

		ResponseEntity<String> allowed = post(team, null, "gpt-4o-mini");
		assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);

		// every decision logged with the specific rule that matched
		assertThat(output.getOut()).contains("policy decision=DENY");
		assertThat(output.getOut()).contains("rule=" + rule.getId());
	}

	@Test
	void projectOverrideWinsOverTeamRule() {
		String team = "policy-team-" + UUID.randomUUID();
		String project = "policy-project-" + UUID.randomUUID();
		policyService.upsertRule("team", team, "gpt-4o-mini", "deny", null);
		policyService.upsertRule("project", project, "claude-*", "deny", null);

		// team alone would deny claude; the project override allows it
		ResponseEntity<String> viaProject = post(team, project, "claude-sonnet-4-5");
		assertThat(viaProject.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(viaProject.getBody()).contains("[mock anthropic]");

		// and the override applies to the project scope only
		ResponseEntity<String> teamOnly = post(team, null, "claude-sonnet-4-5");
		assertThat(teamOnly.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void downgradeServesTheRequestOnTheCheaperModel() throws Exception {
		String team = "policy-team-" + UUID.randomUUID();
		policyService.upsertRule("team", team, "gpt-4o-mini", "downgrade", "gpt-4o-mini");

		ResponseEntity<String> response = post(team, null, "gpt-4o");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst("X-CostPilot-Model-Downgraded"))
				.isEqualTo("gpt-4o -> gpt-4o-mini");
		// executed (not requested) model everywhere downstream: mock echoes it,
		// and the ledger bills it
		assertThat(response.getBody()).contains("\"model\":\"gpt-4o-mini\"");
		Thread.sleep(300);
		assertThat(usageRepository.findAll().stream()
				.filter(r -> team.equals(r.getTeamId()))
				.allMatch(r -> r.getModel().equals("gpt-4o-mini"))).isTrue();
	}

	@Test
	void requireApprovalIsRejectedWithStableTypeUntilStage8() {
		String team = "policy-team-" + UUID.randomUUID();
		policyService.upsertRule("team", team, "gpt-4o-mini", "require_approval", null);

		ResponseEntity<String> response = post(team, null, "gpt-4o");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).contains("\"type\":\"approval_required\"");
	}

	@Test
	void policyChangeTakesEffectWithoutRedeploy() {
		String team = "policy-team-" + UUID.randomUUID();
		policyService.upsertRule("team", team, "gpt-4o-mini", "deny", null);

		assertThat(post(team, null, "gemini-2.5-flash").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// widen the rule at runtime - same running app, next request passes
		policyService.upsertRule("team", team, "gpt-4o-mini,gemini-*", "deny", null);
		assertThat(post(team, null, "gemini-2.5-flash").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void noRuleMeansDefaultAllow() {
		ResponseEntity<String> response = post("ungoverned-team-" + UUID.randomUUID(), null, "gpt-4o-mini");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void patternMatchingCoversExactWildcardAndStar() {
		assertThat(PolicyService.matches("gpt-4o-mini", "gpt-4o-mini")).isTrue();
		assertThat(PolicyService.matches("gpt-4o-mini", "gpt-4o")).isFalse();
		assertThat(PolicyService.matches("claude-*", "claude-sonnet-4-5")).isTrue();
		assertThat(PolicyService.matches("claude-*", "gemini-2.5-pro")).isFalse();
		assertThat(PolicyService.matches("*", "anything-at-all")).isTrue();
		assertThat(PolicyService.matches("gpt-4o-mini, claude-*", "claude-haiku-4-5")).isTrue();
	}
}
