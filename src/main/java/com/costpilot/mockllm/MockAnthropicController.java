package com.costpilot.mockllm;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Embedded mock of the Anthropic Messages API (POST /v1/messages), including the
// event-typed SSE protocol (message_start .. message_stop) and usage blocks.
@RestController
@ConditionalOnProperty(name = "costpilot.mock-upstream.enabled", havingValue = "true", matchIfMissing = true)
public class MockAnthropicController {

	static final String MOCK_PREFIX = "[mock anthropic] ";

	@SuppressWarnings("unchecked")
	@PostMapping(value = "/mock/anthropic/v1/messages", produces = { MediaType.APPLICATION_JSON_VALUE,
			MediaType.TEXT_EVENT_STREAM_VALUE })
	public Object messages(@RequestBody Map<String, Object> request) {
		String model = (String) request.getOrDefault("model", "claude-sonnet-4-5");
		List<Map<String, Object>> messages = (List<Map<String, Object>>) request.getOrDefault("messages", List.of());
		String lastContent = messages.isEmpty() ? "" : String.valueOf(messages.get(messages.size() - 1).get("content"));
		String content = MOCK_PREFIX + lastContent;
		String id = "msg-mock-" + Math.abs(content.hashCode());
		int inputTokens = MockTokens.count(lastContent);
		int outputTokens = MockTokens.count(content);

		if (Boolean.TRUE.equals(request.get("stream"))) {
			return stream(id, model, content, inputTokens, outputTokens);
		}
		return Map.of(
				"id", id,
				"type", "message",
				"role", "assistant",
				"model", model,
				"content", List.of(Map.of("type", "text", "text", content)),
				"stop_reason", "end_turn",
				"usage", Map.of("input_tokens", inputTokens, "output_tokens", outputTokens));
	}

	private SseEmitter stream(String id, String model, String content, int inputTokens, int outputTokens) {
		SseEmitter emitter = new SseEmitter(30_000L);
		Thread.startVirtualThread(() -> {
			try {
				send(emitter, "message_start", Map.of("type", "message_start", "message", Map.of(
						"id", id, "type", "message", "role", "assistant", "model", model,
						"content", List.of(), "usage", Map.of("input_tokens", inputTokens, "output_tokens", 0))));
				send(emitter, "content_block_start", Map.of("type", "content_block_start", "index", 0,
						"content_block", Map.of("type", "text", "text", "")));
				for (String token : content.split("(?<= )")) {
					Thread.sleep(20);
					send(emitter, "content_block_delta", Map.of("type", "content_block_delta", "index", 0,
							"delta", Map.of("type", "text_delta", "text", token)));
				}
				send(emitter, "content_block_stop", Map.of("type", "content_block_stop", "index", 0));
				send(emitter, "message_delta", Map.of("type", "message_delta",
						"delta", Map.of("stop_reason", "end_turn"),
						"usage", Map.of("output_tokens", outputTokens)));
				send(emitter, "message_stop", Map.of("type", "message_stop"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});
		return emitter;
	}

	private void send(SseEmitter emitter, String event, Object payload) throws IOException {
		emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
	}
}
