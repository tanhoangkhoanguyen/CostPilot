package com.costpilot.api.dto;

import java.util.List;

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
}
