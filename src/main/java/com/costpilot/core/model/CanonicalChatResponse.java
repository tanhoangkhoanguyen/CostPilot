package com.costpilot.core.model;

// Provider-neutral response every adapter maps TO.
public record CanonicalChatResponse(
		String id,
		String model,
		String content,
		String finishReason,
		Usage usage) {
}
