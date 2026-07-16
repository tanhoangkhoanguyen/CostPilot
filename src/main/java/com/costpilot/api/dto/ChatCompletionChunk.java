package com.costpilot.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatCompletionChunk(
		String id,
		String object,
		long created,
		String model,
		List<ChunkChoice> choices) {

	public record ChunkChoice(
			int index,
			Delta delta,
			@JsonProperty("finish_reason") String finishReason) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Delta(String role, String content) {
	}
}
