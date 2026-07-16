package com.costpilot.provider.openai;

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

// Fixtures mirror the embedded mock server's OpenAI-shaped output, so these
// unit tests exercise exactly what the gateway sees in dev and in the ITs.
class OpenAiAdapterTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final OpenAiAdapter adapter = new OpenAiAdapter(mapper);

	private CanonicalChatRequest request(boolean stream) {
		return new CanonicalChatRequest("gpt-4o-mini",
				List.of(new CanonicalChatRequest.Message("user", "hello costpilot")), 128, stream);
	}

	@Test
	void buildsOpenAiWireRequest() {
		JsonNode body = mapper.valueToTree(adapter.buildUpstreamBody(request(false)));

		assertThat(body.get("model").asText()).isEqualTo("gpt-4o-mini");
		assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
		assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("hello costpilot");
		assertThat(body.get("stream").asBoolean()).isFalse();
		assertThat(body.get("max_tokens").asInt()).isEqualTo(128);
	}

	@Test
	void omitsMaxTokensWhenAbsent() {
		CanonicalChatRequest noMax = new CanonicalChatRequest("gpt-4o-mini",
				List.of(new CanonicalChatRequest.Message("user", "hi")), null, false);
		JsonNode body = mapper.valueToTree(adapter.buildUpstreamBody(noMax));
		assertThat(body.has("max_tokens")).isFalse();
	}

	@Test
	void parsesNonStreamingResponseWithUsage() {
		String body = """
				{"id":"chatcmpl-mock-1","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
				 "choices":[{"index":0,"message":{"role":"assistant","content":"[mock openai] hello costpilot"},
				             "finish_reason":"stop"}],
				 "usage":{"prompt_tokens":2,"completion_tokens":4,"total_tokens":6}}
				""";

		CanonicalChatResponse response = adapter.parseResponse(body);

		assertThat(response.id()).isEqualTo("chatcmpl-mock-1");
		assertThat(response.model()).isEqualTo("gpt-4o-mini");
		assertThat(response.content()).isEqualTo("[mock openai] hello costpilot");
		assertThat(response.finishReason()).isEqualTo("stop");
		assertThat(response.usage().inputTokens()).isEqualTo(2);
		assertThat(response.usage().outputTokens()).isEqualTo(4);
		assertThat(response.usage().totalTokens()).isEqualTo(6);
	}

	@Test
	void parsesStreamEvents() {
		Optional<CanonicalStreamChunk> role = adapter.parseStreamEvent(
				"{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
		assertThat(role).hasValueSatisfying(c -> assertThat(c.roleDelta()).isEqualTo("assistant"));

		Optional<CanonicalStreamChunk> content = adapter.parseStreamEvent(
				"{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello \"}}]}");
		assertThat(content).hasValueSatisfying(c -> assertThat(c.contentDelta()).isEqualTo("hello "));

		Optional<CanonicalStreamChunk> finish = adapter.parseStreamEvent(
				"{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
		assertThat(finish).hasValueSatisfying(c -> assertThat(c.finishReason()).isEqualTo("stop"));

		Optional<CanonicalStreamChunk> usage = adapter.parseStreamEvent(
				"{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}");
		assertThat(usage).hasValueSatisfying(c -> {
			assertThat(c.usage().inputTokens()).isEqualTo(3);
			assertThat(c.usage().outputTokens()).isEqualTo(5);
		});

		Optional<CanonicalStreamChunk> done = adapter.parseStreamEvent("[DONE]");
		assertThat(done).hasValueSatisfying(c -> assertThat(c.done()).isTrue());

		assertThat(adapter.parseStreamEvent("{}")).isEmpty();
	}

	@Test
	void appliesBearerAuthOnlyWhenKeyPresent() {
		HttpHeaders with = new HttpHeaders();
		adapter.applyAuth(with, "sk-test");
		assertThat(with.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");

		HttpHeaders without = new HttpHeaders();
		adapter.applyAuth(without, null);
		adapter.applyAuth(without, "");
		assertThat(without.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
	}
}
