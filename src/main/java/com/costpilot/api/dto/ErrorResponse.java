package com.costpilot.api.dto;

// OpenAI-style error envelope: {"error":{"message":...,"type":...}}
public record ErrorResponse(ErrorBody error) {

	public record ErrorBody(String message, String type) {
	}

	public static ErrorResponse invalidRequest(String message) {
		return new ErrorResponse(new ErrorBody(message, "invalid_request_error"));
	}
}
