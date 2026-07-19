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
import com.costpilot.domain.AdminAuditRepository;
import com.costpilot.security.AuthTestSupport;

// 9.1 acceptance: setting a budget/policy via the admin API changes enforcement
// immediately (no redeploy), every admin action is audited, and the admin surface is
// ROLE_ADMIN only.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AdminControlSurfaceIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AdminAuditRepository adminAudit;

	private HttpHeaders adminJson() {
		HttpHeaders h = new HttpHeaders();
		h.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		h.setContentType(MediaType.APPLICATION_JSON);
		return h;
	}

	private ResponseEntity<String> chat(String team, String model) {
		HttpHeaders h = adminJson();
		h.set("X-Team-ID", team);
		String body = """
				{"model":"%s","messages":[{"role":"user","content":"hi"}],"stream":false,"max_tokens":16}
				""".formatted(model);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(body, h), String.class);
	}

	@Test
	void settingAPolicyViaApiChangesEnforcementImmediately() {
		String team = "admin-pol-" + UUID.randomUUID();
		// baseline: no rule -> default-open, gpt-4o allowed
		assertThat(chat(team, "gpt-4o").getStatusCode()).isEqualTo(HttpStatus.OK);

		// deny everything but gpt-4o-mini via the admin API
		String body = """
				{"scopeType":"team","scopeRef":"%s","allowedModels":"gpt-4o-mini","fallbackAction":"deny"}
				""".formatted(team);
		ResponseEntity<String> put = restTemplate.exchange("/admin/policies", HttpMethod.PUT,
				new HttpEntity<>(body, adminJson()), String.class);
		assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

		// enforcement is immediate: gpt-4o is now denied (403), no redeploy
		assertThat(chat(team, "gpt-4o").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(chat(team, "gpt-4o-mini").getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(adminAudit.findByTargetTypeAndTargetRefOrderByCreatedAtDesc("team", team))
				.anySatisfy(a -> assertThat(a.getAction()).isEqualTo("policy.upsert"));
	}

	@Test
	void settingABudgetViaApiEnforcesImmediatelyAndIsAudited() {
		String team = "admin-bud-" + UUID.randomUUID();
		// a tiny budget that the very next request's estimate exceeds -> blocked (402)
		String body = """
				{"scope":"team","ref":"%s","limit":0.0000001}
				""".formatted(team);
		ResponseEntity<String> put = restTemplate.exchange("/admin/budgets", HttpMethod.PUT,
				new HttpEntity<>(body, adminJson()), String.class);
		assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(chat(team, "gpt-4o").getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);

		assertThat(adminAudit.findByTargetTypeAndTargetRefOrderByCreatedAtDesc("team", team))
				.anySatisfy(a -> assertThat(a.getAction()).isEqualTo("budget.upsert"));

		// deactivate -> the scope reverts to ungoverned, request flows again
		restTemplate.exchange("/admin/budgets?scope=team&ref=" + team, HttpMethod.DELETE,
				new HttpEntity<>(adminJson()), String.class);
		assertThat(chat(team, "gpt-4o").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void nonAdminKeyCannotReachTheAdminControlPlane() {
		HttpHeaders team = new HttpHeaders();
		team.setBearerAuth(AuthTestSupport.TEAM_PLATFORM_KEY);
		team.setContentType(MediaType.APPLICATION_JSON);
		String body = """
				{"scopeType":"team","scopeRef":"x","allowedModels":"gpt-4o","fallbackAction":"deny"}
				""";
		ResponseEntity<String> put = restTemplate.exchange("/admin/policies", HttpMethod.PUT,
				new HttpEntity<>(body, team), String.class);
		assertThat(put.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
}
