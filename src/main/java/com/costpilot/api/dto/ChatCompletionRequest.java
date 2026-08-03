package com.costpilot.api.dto;

import java.util.List;

import com.costpilot.core.model.CanonicalChatRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

public record ChatCompletionRequest(
		@NotBlank(message = "model must not be blank") String model,
		@NotEmpty(message = "messages must not be empty") List<@Valid ChatMessage> messages,
		Boolean stream,
		@JsonProperty("max_tokens") @Positive(message = "max_tokens must be positive") Integer maxTokens) {

	public boolean isStreaming() {
		return Boolean.TRUE.equals(stream);
	}

	/**
	 * Wire schema -> provider-neutral request. This mapping used to live on
	 * {@code CanonicalChatRequest.from(...)}, which forced {@code core.model} to import the
	 * web layer and dragged it into a package cycle (#100). The DTO knowing how to
	 * normalise itself is the direction that does not couple the core to HTTP.
	 */
	public CanonicalChatRequest toCanonical() {
		List<CanonicalChatRequest.Message> canonicalMessages = messages.stream()
				.map(m -> new CanonicalChatRequest.Message(m.role(), m.content()))
				.toList();
		return new CanonicalChatRequest(model, canonicalMessages, maxTokens, isStreaming());
	}
}
