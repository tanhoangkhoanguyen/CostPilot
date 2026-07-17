package com.costpilot.core.model;

import java.util.List;

import com.costpilot.api.dto.ChatCompletionRequest;

// Provider-neutral request every adapter maps FROM. The gateway's public contract
// (OpenAI schema) is normalized into this before any provider-specific code runs.
public record CanonicalChatRequest(
		String model,
		List<Message> messages,
		Integer maxTokens,
		boolean stream) {

	public record Message(String role, String content) {
	}

	public static CanonicalChatRequest from(ChatCompletionRequest dto) {
		List<Message> messages = dto.messages().stream()
				.map(m -> new Message(m.role(), m.content()))
				.toList();
		return new CanonicalChatRequest(dto.model(), messages, dto.maxTokens(), dto.isStreaming());
	}
}
