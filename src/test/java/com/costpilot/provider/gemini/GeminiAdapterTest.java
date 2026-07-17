package com.costpilot.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class GeminiAdapterTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final GeminiAdapter adapter = new GeminiAdapter(mapper);

	@Test
	void modelRidesInThePath() {
		CanonicalChatRequest nonStream = new CanonicalChatRequest("gemini-2.5-flash",
				List.of(new CanonicalChatRequest.Message("user", "hi")), null, false);
		CanonicalChatRequest stream = new CanonicalChatRequest("gemini-2.5-flash",
				List.of(new CanonicalChatRequest.Message("user", "hi")), null, true);

		assertThat(adapter.chatPath(nonStream))
				.isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
		assertThat(adapter.chatPath(stream))
				.isEqualTo("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse");
	}

	@Test
	void buildsGeminiWireRequest() {
		CanonicalChatRequest request = new CanonicalChatRequest("gemini-2.5-pro",
				List.of(new CanonicalChatRequest.Message("system", "be terse"),
						new CanonicalChatRequest.Message("user", "hello costpilot"),
						new CanonicalChatRequest.Message("assistant", "hi!"),
						new CanonicalChatRequest.Message("user", "again")),
				64, false);

		JsonNode body = mapper.valueToTree(adapter.buildUpstreamBody(request));

		assertThat(body.get("systemInstruction").get("parts").get(0).get("text").asText()).isEqualTo("be terse");
		assertThat(body.get("contents")).hasSize(3);
		assertThat(body.get("contents").get(0).get("role").asText()).isEqualTo("user");
		assertThat(body.get("contents").get(1).get("role").asText()).isEqualTo("model");
		assertThat(body.get("generationConfig").get("maxOutputTokens").asInt()).isEqualTo(64);
	}

	@Test
	void appliesGoogApiKeyHeader() {
		HttpHeaders headers = new HttpHeaders();
		adapter.applyAuth(headers, "g-key");
		assertThat(headers.getFirst("x-goog-api-key")).isEqualTo("g-key");
	}

	@Test
	void parsesGenerateContentResponse() {
		String body = """
				{"candidates":[{"content":{"parts":[{"text":"[mock gemini] hello costpilot"}],"role":"model"},
				               "finishReason":"STOP","index":0}],
				 "usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":5,"totalTokenCount":7}}
				""";

		CanonicalChatResponse response = adapter.parseResponse(body);

		assertThat(response.content()).isEqualTo("[mock gemini] hello costpilot");
		assertThat(response.finishReason()).isEqualTo("stop");
		assertThat(response.usage().inputTokens()).isEqualTo(2);
		assertThat(response.usage().outputTokens()).isEqualTo(5);
	}

	@Test
	void parsesStreamChunksWithoutDoneSentinel() {
		Optional<CanonicalStreamChunk> mid = adapter.parseStreamEvent(
				"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello \"}],\"role\":\"model\"},\"index\":0}]}");
		assertThat(mid).hasValueSatisfying(c -> {
			assertThat(c.contentDelta()).isEqualTo("hello ");
			assertThat(c.finishReason()).isNull();
		});

		Optional<CanonicalStreamChunk> last = adapter.parseStreamEvent("""
				{"candidates":[{"content":{"parts":[{"text":"costpilot"}],"role":"model"},
				               "finishReason":"STOP","index":0}],
				 "usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":5,"totalTokenCount":7}}
				""");
		assertThat(last).hasValueSatisfying(c -> {
			assertThat(c.contentDelta()).isEqualTo("costpilot");
			assertThat(c.finishReason()).isEqualTo("stop");
			assertThat(c.usage().outputTokens()).isEqualTo(5);
		});

		assertThat(adapter.parseStreamEvent("{\"candidates\":[{\"content\":{\"parts\":[]},\"index\":0}]}")).isEmpty();
	}
}
