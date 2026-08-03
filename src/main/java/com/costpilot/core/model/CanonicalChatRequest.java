package com.costpilot.core.model;

import java.util.List;

// Provider-neutral request every adapter maps FROM. The gateway's public contract
// (OpenAI schema) is normalized into this before any provider-specific code runs.
//
// The wire -> canonical mapping deliberately lives on the DTO
// (ChatCompletionRequest#toCanonical), not here: core.model is the leaf every other
// package depends on, so it must not know about the web layer. It used to, via a
// `from(ChatCompletionRequest)` factory, and that single edge put core.model inside a
// twelve-package dependency cycle (#100).
public record CanonicalChatRequest(
		String model,
		List<Message> messages,
		Integer maxTokens,
		boolean stream) {

	public record Message(String role, String content) {
	}
}
