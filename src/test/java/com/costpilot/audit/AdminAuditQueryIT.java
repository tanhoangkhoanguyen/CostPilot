package com.costpilot.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.domain.AuditRecord;
import com.costpilot.domain.AuditRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;

// 5.1 admin query: filter by team / project / decision / time, paginated, newest first.
// Seeds audit rows directly (deterministic) and exercises the HTTP query surface.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AdminAuditQueryIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AuditRecordRepository audit;

	private String teamA;
	private String teamB;

	@BeforeEach
	void seed() {
		audit.deleteAll();
		teamA = "query-a-" + UUID.randomUUID();
		teamB = "query-b-" + UUID.randomUUID();
		// team A: an allow and a deny; team B: one downgrade
		audit.save(row(teamA, "proj-1", "allow"));
		audit.save(row(teamA, "proj-2", "deny"));
		audit.save(row(teamB, "proj-1", "downgrade"));
	}

	private AuditRecord row(String team, String project, String decision) {
		return AuditRecord.builder()
				.teamId(team).projectId(project)
				.requestedModel("gpt-4o").executedModel("gpt-4o")
				.decision(decision)
				.build();
	}

	private JsonNode get(String query) {
		ResponseEntity<JsonNode> response = restTemplate.getForEntity("/admin/audit" + query, JsonNode.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	@Test
	void filtersByTeam() {
		JsonNode page = get("?teamId=" + teamA);
		assertThat(page.get("totalElements").asInt()).isEqualTo(2);
		page.get("content").forEach(r -> assertThat(r.get("teamId").asText()).isEqualTo(teamA));
	}

	@Test
	void filtersByDecision() {
		JsonNode page = get("?decision=deny");
		assertThat(page.get("totalElements").asInt()).isEqualTo(1);
		assertThat(page.get("content").get(0).get("decision").asText()).isEqualTo("deny");
		assertThat(page.get("content").get(0).get("teamId").asText()).isEqualTo(teamA);
	}

	@Test
	void filtersByTeamAndProjectTogether() {
		JsonNode page = get("?teamId=" + teamA + "&projectId=proj-2");
		assertThat(page.get("totalElements").asInt()).isEqualTo(1);
		assertThat(page.get("content").get(0).get("decision").asText()).isEqualTo("deny");
	}

	@Test
	void filtersByTimeWindow() {
		Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
		JsonNode none = get("?from=" + future);
		assertThat(none.get("totalElements").asInt()).isZero();

		Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
		JsonNode all = get("?from=" + past);
		assertThat(all.get("totalElements").asInt()).isEqualTo(3);
	}

	@Test
	void paginates() {
		JsonNode page = get("?size=2");
		assertThat(page.get("content")).hasSize(2);
		assertThat(page.get("totalElements").asInt()).isEqualTo(3);
		assertThat(page.get("totalPages").asInt()).isEqualTo(2);
	}

	@Test
	void ordersNewestFirst() {
		JsonNode page = get("");
		JsonNode content = page.get("content");
		Instant first = Instant.parse(content.get(0).get("createdAt").asText());
		Instant last = Instant.parse(content.get(content.size() - 1).get("createdAt").asText());
		assertThat(first).isAfterOrEqualTo(last);
	}
}
