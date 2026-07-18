package com.costpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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
import com.costpilot.domain.ApiKeyRepository;
import com.fasterxml.jackson.databind.JsonNode;

// 6.1 acceptance: an admin can mint a key; the returned plaintext authenticates; the DB
// stores only the hash (never the plaintext). A non-admin cannot mint.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class KeyMintIT {

	private static final String PLATFORM_TEAM_ID = "00000000-0000-0000-0000-000000000011";

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ApiKeyRepository apiKeys;

	@Autowired
	private ApiKeyHasher hasher;

	private ResponseEntity<JsonNode> mint(String rawKey, Map<String, Object> body) {
		HttpHeaders headers = AuthTestSupport.bearer(rawKey);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange("/admin/keys", HttpMethod.POST,
				new HttpEntity<>(body, headers), JsonNode.class);
	}

	@Test
	void adminMintsAKeyThatAuthenticatesAndIsStoredHashedOnly() {
		ResponseEntity<JsonNode> minted = mint(AuthTestSupport.ADMIN_KEY,
				Map.of("teamId", PLATFORM_TEAM_ID, "name", "ci-minted", "admin", false));
		assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.OK);

		String plaintext = minted.getBody().get("key").asText();
		assertThat(plaintext).startsWith("cp_live_");

		// the DB stores only the hash - never the plaintext
		assertThat(apiKeys.findByKeyHash(plaintext)).isEmpty();
		assertThat(apiKeys.findByKeyHash(hasher.hash(plaintext))).isPresent();

		// and the minted plaintext really authenticates a chat request
		HttpHeaders headers = AuthTestSupport.bearer(plaintext);
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> chat = restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(Map.of("model", "gpt-4o-mini",
						"messages", List.of(Map.of("role", "user", "content", "hi")),
						"stream", false), headers),
				String.class);
		assertThat(chat.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void nonAdminCannotMint() {
		ResponseEntity<JsonNode> response = mint(AuthTestSupport.TEAM_PLATFORM_KEY,
				Map.of("teamId", PLATFORM_TEAM_ID, "name", "nope", "admin", false));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
}
