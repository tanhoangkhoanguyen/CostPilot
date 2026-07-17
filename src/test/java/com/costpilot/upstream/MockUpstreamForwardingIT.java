package com.costpilot.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

// End-to-end: gateway -> embedded mock LLM upstream -> back through the gateway.
// No network beyond localhost, no provider keys, fully deterministic.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MockUpstreamForwardingIT {

	@Autowired
	private TestRestTemplate restTemplate;

	private static final String BODY = """
			{
			  "model": "gpt-4o-mini",
			  "messages": [{"role": "user", "content": "hello costpilot"}],
			  "stream": %s
			}
			""";

	@Test
	void gatewayForwardsNonStreamingToMockAndRelaysResponse() {
		ResponseEntity<String> response = restTemplate.exchange(
				"/v1/chat/completions", HttpMethod.POST, jsonEntity(BODY.formatted("false")), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("[mock openai] hello costpilot");
		assertThat(response.getBody()).contains("\"usage\"");
		assertThat(response.getBody()).contains("\"prompt_tokens\"");
	}

	@Test
	void streamingPassthroughFromMockWorksEndToEnd() {
		List<String> lines = restTemplate.execute("/v1/chat/completions", HttpMethod.POST,
				request -> {
					request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
					request.getHeaders().setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
					request.getBody().write(BODY.formatted("true").getBytes(StandardCharsets.UTF_8));
				},
				response -> {
					List<String> collected = new ArrayList<>();
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
						String line;
						while ((line = reader.readLine()) != null) {
							if (!line.isBlank()) {
								collected.add(line);
							}
						}
					}
					return collected;
				});

		assertThat(lines).isNotEmpty();
		assertThat(String.join("\n", lines)).contains("chat.completion.chunk");
		assertThat(String.join("\n", lines)).contains("[mock");
		assertThat(lines.get(lines.size() - 1)).isEqualTo("data:[DONE]");
	}

	private HttpEntity<String> jsonEntity(String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(body, headers);
	}
}
