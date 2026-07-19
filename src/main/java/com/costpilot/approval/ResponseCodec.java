package com.costpilot.approval;

import org.springframework.stereotype.Component;

import com.costpilot.api.dto.ChatCompletionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serializes the rendered OpenAI-shaped response to/from the JSON string stored on an
 * approved request, so the caller can retrieve the exact response by the pending handle.
 */
@Component
public class ResponseCodec {

	private final ObjectMapper objectMapper;

	public ResponseCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String serialize(ChatCompletionResponse response) {
		try {
			return objectMapper.writeValueAsString(response);
		} catch (Exception e) {
			throw new IllegalStateException("unserializable approval response", e);
		}
	}

	public ChatCompletionResponse deserialize(String json) {
		try {
			return objectMapper.readValue(json, ChatCompletionResponse.class);
		} catch (Exception e) {
			throw new IllegalStateException("undeserializable approval response", e);
		}
	}
}
