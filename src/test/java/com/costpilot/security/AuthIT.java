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
import com.costpilot.domain.ApiKey;
import com.costpilot.domain.ApiKeyRepository;

// 6.1 acceptance: unauthenticated requests are blocked; a valid seeded key is accepted;
// a revoked key is rejected. Exercises the real filter chain end-to-end.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ApiKeyRepository apiKeys;

	@Autowired
	private ApiKeyHasher hasher;

	private static final Map<String, Object> BODY = Map.of(
			"model", "gpt-4o-mini",
			"messages", List.of(Map.of("role", "user", "content", "hi")),
			"stream", false);

	private ResponseEntity<String> post(HttpHeaders headers) {
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY, headers), String.class);
	}

	@Test
	void unauthenticatedChatIsBlocked() {
		assertThat(post(new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unauthenticatedAdminIsBlocked() {
		ResponseEntity<String> response = restTemplate.getForEntity("/admin/audit", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void validSeededKeyIsAccepted() {
		// proves the PowerShell-computed seed hash matches the Java HMAC hasher
		ResponseEntity<String> response = post(AuthTestSupport.bearer(AuthTestSupport.TEAM_PLATFORM_KEY));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void adminKeyIsAccepted() {
		assertThat(post(AuthTestSupport.admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unknownKeyIsRejected() {
		assertThat(post(AuthTestSupport.bearer("cp_not_a_real_key")).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void revokedKeyIsRejected() {
		// mint-and-revoke a throwaway key, then confirm it no longer authenticates
		String raw = "cp_to_revoke_" + java.util.UUID.randomUUID();
		ApiKey key = new ApiKey(java.util.UUID.fromString("00000000-0000-0000-0000-000000000011"),
				null, hasher.hash(raw), "revoked-test", false);
		setRevoked(key);
		apiKeys.save(key);

		assertThat(post(AuthTestSupport.bearer(raw)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private static void setRevoked(ApiKey key) {
		try {
			var field = ApiKey.class.getDeclaredField("revokedAt");
			field.setAccessible(true);
			field.set(key, java.time.Instant.now());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
