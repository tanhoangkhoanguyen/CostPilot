package com.costpilot.approval;

import org.springframework.stereotype.Component;

import com.costpilot.core.model.CanonicalChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serializes the canonical request to/from the JSON string stored on a parked approval,
 * so an approved request replays byte-for-byte. CanonicalChatRequest is a plain record
 * of records - Jackson round-trips it directly.
 */
@Component
public class RequestPayloadCodec {

	private final ObjectMapper objectMapper;

	public RequestPayloadCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String serialize(CanonicalChatRequest request) {
		try {
			return objectMapper.writeValueAsString(request);
		} catch (Exception e) {
			throw new IllegalStateException("unserializable approval request payload", e);
		}
	}

	public CanonicalChatRequest deserialize(String json) {
		try {
			return objectMapper.readValue(json, CanonicalChatRequest.class);
		} catch (Exception e) {
			throw new IllegalStateException("undeserializable approval request payload", e);
		}
	}
}
