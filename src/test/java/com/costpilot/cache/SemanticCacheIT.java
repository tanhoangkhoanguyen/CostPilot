package com.costpilot.cache;

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
import com.costpilot.security.AuthTestSupport;

// 10.2 acceptance: a similar prompt is served from cache with no provider call (no new
// ledger row) and the cost recorded as saved; tenants cannot hit each other's cache; a
// distinct prompt misses.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "costpilot.cache.enabled=true", "costpilot.cache.similarity-threshold=0.97" })
@Import(TestcontainersConfiguration.class)
class SemanticCacheIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private UsageRecordRepository usageRepository;

	private ResponseEntity<String> chat(String team, String prompt) {
		HttpHeaders h = new HttpHeaders();
		h.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		h.setContentType(MediaType.APPLICATION_JSON);
		h.set("X-Team-ID", team);
		String body = """
				{"model":"gpt-4o-mini","messages":[{"role":"user","content":"%s"}],"stream":false,"max_tokens":32}
				""".formatted(prompt);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(body, h), String.class);
	}

	@Test
	void aRepeatedPromptIsServedFromCacheWithNoProviderCall() {
		String team = "cache-hit-" + UUID.randomUUID();
		String prompt = "summarize the quarterly revenue report for the board";

		// first request: miss -> forwarded -> stored (one ledger row)
		ResponseEntity<String> first = chat(team, prompt);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getHeaders().getFirst("X-CostPilot-Cache")).isNull();
		long afterFirst = teamRows(team);
		assertThat(afterFirst).isEqualTo(1);

		// second, identical prompt: hit -> no forward -> NO new ledger row
		ResponseEntity<String> second = chat(team, prompt);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getHeaders().getFirst("X-CostPilot-Cache")).isEqualTo("hit");
		assertThat(teamRows(team)).isEqualTo(afterFirst);
		// the cached answer is returned verbatim
		assertThat(second.getBody()).contains("chat.completion");
	}

	@Test
	void tenantsCannotHitEachOthersCache() {
		String prompt = "explain the isolation guarantee in detail please";
		String teamA = "cache-iso-a-" + UUID.randomUUID();
		String teamB = "cache-iso-b-" + UUID.randomUUID();

		assertThat(chat(teamA, prompt).getStatusCode()).isEqualTo(HttpStatus.OK);
		// team B sends the SAME prompt - but the cache is team-scoped, so it must miss
		// and forward (its own ledger row appears)
		ResponseEntity<String> b = chat(teamB, prompt);
		assertThat(b.getHeaders().getFirst("X-CostPilot-Cache")).isNull();
		assertThat(teamRows(teamB)).isEqualTo(1);
	}

	@Test
	void aDistinctPromptMisses() {
		String team = "cache-distinct-" + UUID.randomUUID();
		assertThat(chat(team, "what is the capital of france").getStatusCode()).isEqualTo(HttpStatus.OK);
		// a totally different prompt is below the threshold -> miss -> forwarded
		ResponseEntity<String> other = chat(team, "generate a python script to sort a list");
		assertThat(other.getHeaders().getFirst("X-CostPilot-Cache")).isNull();
		assertThat(teamRows(team)).isEqualTo(2);
	}

	private long teamRows(String team) {
		return usageRepository.findAll().stream().filter(r -> team.equals(r.getTeamId())).count();
	}
}
