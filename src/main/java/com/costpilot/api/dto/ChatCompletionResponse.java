package com.costpilot.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatCompletionResponse(
		String id,
		String object,
		long created,
		String model,
		List<Choice> choices,
		Usage usage) {

	public record Choice(
			int index,
			ChatMessage message,
			@JsonProperty("finish_reason") String finishReason) {
	}

	public record Usage(
			@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("completion_tokens") int completionTokens,
			@JsonProperty("total_tokens") int totalTokens) {
	}
}
