package com.costpilot.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// OpenAI-style error envelope: {"error":{"message":...,"type":...,"code":...}}
public record ErrorResponse(ErrorBody error) {

	public record ErrorBody(String message, String type,
			@JsonInclude(JsonInclude.Include.NON_NULL) String code) {
	}

	public static ErrorResponse invalidRequest(String message) {
		return new ErrorResponse(new ErrorBody(message, "invalid_request_error", null));
	}

	public static ErrorResponse budgetExceeded(String message, String scope) {
		return new ErrorResponse(new ErrorBody(message, "budget_exceeded", scope));
	}
}
