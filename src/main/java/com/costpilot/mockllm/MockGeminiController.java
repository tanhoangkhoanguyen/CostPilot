package com.costpilot.mockllm;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Embedded mock of the Gemini generateContent API, incl. streamGenerateContent SSE
// (alt=sse shape: full GenerateContentResponse per chunk) and usageMetadata.
@RestController
@ConditionalOnProperty(name = "costpilot.mock-upstream.enabled", havingValue = "true", matchIfMissing = true)
public class MockGeminiController {

	static final String MOCK_PREFIX = "[mock gemini] ";

	@PostMapping(value = "/mock/gemini/v1beta/models/{model}:generateContent", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> generateContent(@PathVariable String model,
			@RequestBody Map<String, Object> request) {
		String content = MOCK_PREFIX + lastUserText(request);
		int promptTokens = MockTokens.count(lastUserText(request));
		int candidateTokens = MockTokens.count(content);
		return response(content, "STOP", promptTokens, candidateTokens);
	}

	@PostMapping(value = "/mock/gemini/v1beta/models/{model}:streamGenerateContent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamGenerateContent(@PathVariable String model,
			@RequestBody Map<String, Object> request) {
		String content = MOCK_PREFIX + lastUserText(request);
		int promptTokens = MockTokens.count(lastUserText(request));
		SseEmitter emitter = new SseEmitter(30_000L);
		Thread.startVirtualThread(() -> {
			try {
				String[] tokens = content.split("(?<= )");
				for (int i = 0; i < tokens.length; i++) {
					Thread.sleep(20);
					boolean last = i == tokens.length - 1;
					emitter.send(SseEmitter.event().data(
							response(tokens[i], last ? "STOP" : null, promptTokens,
									last ? MockTokens.count(content) : 0),
							MediaType.APPLICATION_JSON));
				}
				emitter.complete();
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		});
		return emitter;
	}

	private Map<String, Object> response(String text, String finishReason, int promptTokens, int candidateTokens) {
		Map<String, Object> candidate = finishReason == null
				? Map.of("content", Map.of("parts", List.of(Map.of("text", text)), "role", "model"), "index", 0)
				: Map.of("content", Map.of("parts", List.of(Map.of("text", text)), "role", "model"),
						"finishReason", finishReason, "index", 0);
		return Map.of(
				"candidates", List.of(candidate),
				"usageMetadata", Map.of(
						"promptTokenCount", promptTokens,
						"candidatesTokenCount", candidateTokens,
						"totalTokenCount", promptTokens + candidateTokens));
	}

	@SuppressWarnings("unchecked")
	private String lastUserText(Map<String, Object> request) {
		List<Map<String, Object>> contents = (List<Map<String, Object>>) request.getOrDefault("contents", List.of());
		if (contents.isEmpty()) {
			return "";
		}
		List<Map<String, Object>> parts = (List<Map<String, Object>>) contents.get(contents.size() - 1)
				.getOrDefault("parts", List.of());
		return parts.isEmpty() ? "" : String.valueOf(parts.get(parts.size() - 1).get("text"));
	}
}
