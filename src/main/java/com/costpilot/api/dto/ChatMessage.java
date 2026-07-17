package com.costpilot.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatMessage(
		@NotBlank(message = "message role must not be blank") String role,
		@NotBlank(message = "message content must not be blank") String content) {
}
