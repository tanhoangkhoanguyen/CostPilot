package com.costpilot.admin;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.costpilot.domain.UsageRecordRepository;
import com.costpilot.policy.PolicyService;
import com.costpilot.security.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// 9.2 acceptance: the pending list is accurate and approve/reject drive the Stage 8
// state machine end-to-end through the gateway.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApprovalsApiIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private PolicyService policyService;

	@Autowired
	private UsageRecordRepository usageRepository;

	private final ObjectMapper mapper = new ObjectMapper();

	private HttpHeaders admin() {
		HttpHeaders h = new HttpHeaders();
		h.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		h.setContentType(MediaType.APPLICATION_JSON);
		return h;
	}

	// trigger a park: a rule that requires approval for the requested model
	private String parkRequest(String team) throws Exception {
		policyService.upsertRule("team", team, "gpt-4o-mini", "require_approval", null);
		HttpHeaders h = admin();
		h.set("X-Team-ID", team);
		String body = """
				{"model":"gpt-4o","messages":[{"role":"user","content":"gate me"}],"stream":false,"max_tokens":16}
				""";
		ResponseEntity<String> parked = restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(body, h), String.class);
		assertThat(parked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		return mapper.readTree(parked.getBody()).get("id").asText();
	}

	@Test
	void pendingListReflectsParkedRequestsAndApproveCompletesEndToEnd() throws Exception {
		String team = "appr-api-" + UUID.randomUUID();
		String id = parkRequest(team);

		// the pending list includes it
		ResponseEntity<String> list = restTemplate.exchange("/admin/approvals", HttpMethod.GET,
				new HttpEntity<>(admin()), String.class);
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(list.getBody()).contains(id);

		long before = usageRepository.count();
		// approve -> replayed, response returned, ledgered
		ResponseEntity<String> approved = restTemplate.exchange("/admin/approvals/" + id + "/approve",
				HttpMethod.POST, new HttpEntity<>(admin()), String.class);
		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode response = mapper.readTree(approved.getBody());
		assertThat(response.get("object").asText()).isEqualTo("chat.completion");
		assertThat(usageRepository.count()).isEqualTo(before + 1);

		// approving again is a conflict (already decided)
		ResponseEntity<String> again = restTemplate.exchange("/admin/approvals/" + id + "/approve",
				HttpMethod.POST, new HttpEntity<>(admin()), String.class);
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void rejectDrivesTheStateMachineAndNeverForwards() throws Exception {
		String team = "appr-rej-" + UUID.randomUUID();
		String id = parkRequest(team);
		long before = usageRepository.count();

		ResponseEntity<String> rejected = restTemplate.exchange("/admin/approvals/" + id + "/reject",
				HttpMethod.POST, new HttpEntity<>("{\"reason\":\"too costly\"}", admin()), String.class);
		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(rejected.getBody()).contains("\"state\":\"rejected\"");
		// never forwarded
		assertThat(usageRepository.count()).isEqualTo(before);
	}

	@Test
	void nonAdminCannotUseTheApprovalsApi() {
		HttpHeaders team = new HttpHeaders();
		team.setBearerAuth(AuthTestSupport.TEAM_PLATFORM_KEY);
		ResponseEntity<String> list = restTemplate.exchange("/admin/approvals", HttpMethod.GET,
				new HttpEntity<>(team), String.class);
		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
}
