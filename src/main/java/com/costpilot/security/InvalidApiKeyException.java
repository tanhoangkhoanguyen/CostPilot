package com.costpilot.security;

// 6.1: thrown when a presented key is missing, unknown, or revoked. Mapped to 401.
public class InvalidApiKeyException extends RuntimeException {

	public InvalidApiKeyException(String message) {
		super(message);
	}
}
