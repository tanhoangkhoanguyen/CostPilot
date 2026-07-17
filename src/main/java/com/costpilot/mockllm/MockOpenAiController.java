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

import com.costpilot.api.dto.ChatCompletionChunk;
import com.costpilot.api.dto.ChatCompletionRequest;
import com.costpilot.api.dto.ChatCompletionResponse;
import com.costpilot.api.dto.ChatMessage;

// Embedded mock of the OpenAI chat completions API: deterministic content, a real
// usage block, and paced SSE streaming - so dev and the whole test suite cost $0.
@RestController
@ConditionalOnProperty(name = "costpilot.mock-upstream.enabled", havingValue = "true", matchIfMissing = true)
public class MockOpenAiController {

	static final String MOCK_PREFIX = "[mock openai] ";

	// paced streaming; tests with very long generations dial this down
	@org.springframework.beans.factory.annotation.Value("${costpilot.mock-upstream.token-delay-ms:20}")
	private long tokenDelayMs;

	@PostMapping(value = "/mock/openai/v1/chat/completions", produces = { MediaType.APPLICATION_JSON_VALUE,
			MediaType.TEXT_EVENT_STREAM_VALUE })
	public Object chatCompletions(@RequestBody ChatCompletionRequest request) throws IOException {
		String id = "chatcmpl-mock-" + Math.abs(request.messages().hashCode());
		long created = 1_700_000_000L;
		String content = MOCK_PREFIX + lastUserContent(request);
		int promptTokens = MockTokens.count(request);
		int completionTokens = MockTokens.count(content);

		if (request.isStreaming()) {
			return stream(id, created, request.model(), content);
		}
		return new ChatCompletionResponse(
				id, "chat.completion", created, request.model(),
				List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", content), "stop")),
				new ChatCompletionResponse.Usage(promptTokens, completionTokens, promptTokens + completionTokens));
	}

	private SseEmitter stream(String id, long created, String model, String content) {
		SseEmitter emitter = new SseEmitter(30_000L);
		Thread.startVirtualThread(() -> {
			try {
				send(emitter, chunk(id, created, model, new ChatCompletionChunk.Delta("assistant", null), null));
				for (String token : content.split("(?<= )")) {
					Thread.sleep(tokenDelayMs); // paced so first token demonstrably beats completion (1.3)
					send(emitter, chunk(id, created, model, new ChatCompletionChunk.Delta(null, token), null));
				}
				send(emitter, chunk(id, created, model, new ChatCompletionChunk.Delta(null, null), "stop"));
				send(emitter, Map.of("id", id, "object", "chat.completion.chunk", "created", created,
						"model", model, "choices", List.of(),
						"usage", Map.of("prompt_tokens", MockTokens.count(content),
								"completion_tokens", MockTokens.count(content),
								"total_tokens", 2 * MockTokens.count(content))));
				emitter.send(SseEmitter.event().data("[DONE]"));
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});
		return emitter;
	}

	private void send(SseEmitter emitter, Object payload) throws IOException {
		emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
	}

	private ChatCompletionChunk chunk(String id, long created, String model,
			ChatCompletionChunk.Delta delta, String finishReason) {
		return new ChatCompletionChunk(id, "chat.completion.chunk", created, model,
				List.of(new ChatCompletionChunk.ChunkChoice(0, delta, finishReason)));
	}

	private String lastUserContent(ChatCompletionRequest request) {
		return request.messages().get(request.messages().size() - 1).content();
	}
}
