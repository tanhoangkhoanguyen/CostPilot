package com.costpilot.provider.anthropic;

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

class AnthropicAdapterTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final AnthropicAdapter adapter = new AnthropicAdapter(mapper);

	@Test
	void buildsAnthropicWireRequestWithSystemExtracted() {
		CanonicalChatRequest request = new CanonicalChatRequest("claude-sonnet-4-5",
				List.of(new CanonicalChatRequest.Message("system", "be terse"),
						new CanonicalChatRequest.Message("user", "hello costpilot")),
				256, false);

		JsonNode body = mapper.valueToTree(adapter.buildUpstreamBody(request));

		assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-5");
		assertThat(body.get("max_tokens").asInt()).isEqualTo(256);
		assertThat(body.get("system").asText()).isEqualTo("be terse");
		assertThat(body.get("messages")).hasSize(1);
		assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
		assertThat(body.has("stream")).isFalse();
	}

	@Test
	void maxTokensIsAlwaysPresentBecauseAnthropicRequiresIt() {
		CanonicalChatRequest request = new CanonicalChatRequest("claude-haiku-4-5",
				List.of(new CanonicalChatRequest.Message("user", "hi")), null, true);
		JsonNode body = mapper.valueToTree(adapter.buildUpstreamBody(request));
		assertThat(body.get("max_tokens").asInt()).isEqualTo(AnthropicAdapter.DEFAULT_MAX_TOKENS);
		assertThat(body.get("stream").asBoolean()).isTrue();
	}

	@Test
	void appliesApiKeyAndVersionHeaders() {
		HttpHeaders headers = new HttpHeaders();
		adapter.applyAuth(headers, "sk-ant-test");
		assertThat(headers.getFirst("x-api-key")).isEqualTo("sk-ant-test");
		assertThat(headers.getFirst("anthropic-version")).isEqualTo(AnthropicAdapter.ANTHROPIC_VERSION);
	}

	@Test
	void parsesMessagesResponseWithUsage() {
		String body = """
				{"id":"msg-mock-1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
				 "content":[{"type":"text","text":"[mock anthropic] hello costpilot"}],
				 "stop_reason":"end_turn",
				 "usage":{"input_tokens":2,"output_tokens":5}}
				""";

		CanonicalChatResponse response = adapter.parseResponse(body);

		assertThat(response.id()).isEqualTo("msg-mock-1");
		assertThat(response.content()).isEqualTo("[mock anthropic] hello costpilot");
		assertThat(response.finishReason()).isEqualTo("stop");
		assertThat(response.usage().inputTokens()).isEqualTo(2);
		assertThat(response.usage().outputTokens()).isEqualTo(5);
	}

	@Test
	void parsesTypedStreamEvents() {
		assertThat(adapter.parseStreamEvent("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}"))
				.hasValueSatisfying(c -> assertThat(c.roleDelta()).isEqualTo("assistant"));

		assertThat(adapter.parseStreamEvent(
				"{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello \"}}"))
				.hasValueSatisfying(c -> assertThat(c.contentDelta()).isEqualTo("hello "));

		Optional<CanonicalStreamChunk> messageDelta = adapter.parseStreamEvent(
				"{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":7}}");
		assertThat(messageDelta).hasValueSatisfying(c -> {
			assertThat(c.finishReason()).isEqualTo("length");
			assertThat(c.usage().outputTokens()).isEqualTo(7);
		});

		assertThat(adapter.parseStreamEvent("{\"type\":\"message_stop\"}"))
				.hasValueSatisfying(c -> assertThat(c.done()).isTrue());

		assertThat(adapter.parseStreamEvent("{\"type\":\"ping\"}")).isEmpty();
		assertThat(adapter.parseStreamEvent("{\"type\":\"content_block_start\",\"index\":0}")).isEmpty();
	}
}
