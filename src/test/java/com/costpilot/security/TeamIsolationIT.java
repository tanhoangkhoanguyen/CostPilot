package com.costpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.domain.AuditRecord;
import com.costpilot.domain.AuditRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;

// 6.1 acceptance: per-team data isolation - team A cannot read team B's audit rows. The
// seeded team keys resolve to teams 'platform' and 'research'; a tenant-admin sees both.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TeamIsolationIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AuditRecordRepository audit;

	@BeforeEach
	void seed() {
		audit.deleteAll();
		// team names the seeded keys resolve to
		audit.save(row("platform", "allow"));
		audit.save(row("platform", "downgrade"));
		audit.save(row("research", "deny"));
	}

	private AuditRecord row(String team, String decision) {
		return AuditRecord.builder()
				.teamId(team).projectId("proj")
				.requestedModel("gpt-4o").executedModel("gpt-4o")
				.decision(decision)
				.build();
	}

	private JsonNode auditAs(String rawKey, String query) {
		ResponseEntity<JsonNode> response = restTemplate.exchange(
				"/admin/audit" + query, org.springframework.http.HttpMethod.GET,
				new HttpEntity<>(AuthTestSupport.bearer(rawKey)), JsonNode.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	@Test
	void teamKeySeesOnlyItsOwnTeam() {
		JsonNode page = auditAs(AuthTestSupport.TEAM_PLATFORM_KEY, "");
		assertThat(page.get("totalElements").asInt()).isEqualTo(2);
		page.get("content").forEach(r -> assertThat(r.get("teamId").asText()).isEqualTo("platform"));
	}

	@Test
	void teamKeyCannotReadAnotherTeamEvenWhenAskingForIt() {
		// platform key explicitly requests research's rows -> filter is overridden to its own
		JsonNode page = auditAs(AuthTestSupport.TEAM_PLATFORM_KEY, "?teamId=research");
		assertThat(page.get("totalElements").asInt()).isEqualTo(2);
		page.get("content").forEach(r -> assertThat(r.get("teamId").asText()).isEqualTo("platform"));
	}

	@Test
	void researchKeySeesOnlyResearch() {
		JsonNode page = auditAs(AuthTestSupport.TEAM_RESEARCH_KEY, "");
		assertThat(page.get("totalElements").asInt()).isEqualTo(1);
		assertThat(page.get("content").get(0).get("teamId").asText()).isEqualTo("research");
	}

	@Test
	void adminSeesAllTeams() {
		JsonNode page = auditAs(AuthTestSupport.ADMIN_KEY, "");
		assertThat(page.get("totalElements").asInt()).isEqualTo(3);
	}

	@Test
	void adminCanFilterToOneTeam() {
		JsonNode page = auditAs(AuthTestSupport.ADMIN_KEY, "?teamId=research");
		assertThat(page.get("totalElements").asInt()).isEqualTo(1);
		assertThat(page.get("content").get(0).get("teamId").asText()).isEqualTo("research");
	}
}
