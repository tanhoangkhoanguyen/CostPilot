package com.costpilot.mockllm;

import com.costpilot.api.dto.ChatCompletionRequest;
import com.costpilot.api.dto.ChatMessage;

// Deterministic fake token counting for the mock upstream: 1 token per whitespace-separated word.
final class MockTokens {

	private MockTokens() {
	}

	static int count(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return text.trim().split("\\s+").length;
	}

	static int count(ChatCompletionRequest request) {
		return request.messages().stream()
				.map(ChatMessage::content)
				.mapToInt(MockTokens::count)
				.sum();
	}
}
