package com.costpilot.api;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.costpilot.api.dto.ErrorResponse;
import com.costpilot.budget.BudgetExceededException;
import com.costpilot.policy.PolicyDeniedException;
import com.costpilot.security.InvalidApiKeyException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.sorted()
				.collect(Collectors.joining("; "));
		return badRequest(message.isEmpty() ? "invalid request body" : message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
		return badRequest("malformed request body: expected valid JSON matching the chat completions schema");
	}

	@ExceptionHandler(InvalidApiKeyException.class)
	ResponseEntity<ErrorResponse> handleInvalidApiKey(InvalidApiKeyException ex) {
		// 401: missing/unknown/revoked key that slipped past the filter into a controller
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(new ErrorResponse(new ErrorResponse.ErrorBody(
						ex.getMessage(), "unauthorized", null)));
	}

	@ExceptionHandler(BudgetExceededException.class)
	ResponseEntity<ErrorResponse> handleBudgetExceeded(BudgetExceededException ex) {
		// 402: the org's budget says no - machine-readable type + scope code
		return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
				.body(ErrorResponse.budgetExceeded(ex.getMessage(), ex.getScope().dbValue()));
	}

	@ExceptionHandler(PolicyDeniedException.class)
	ResponseEntity<ErrorResponse> handlePolicyDenied(PolicyDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse(new ErrorResponse.ErrorBody(
						ex.getMessage(), "policy_denied", String.valueOf(ex.getDecision().matchedRuleId()))));
	}

	private ResponseEntity<ErrorResponse> badRequest(String message) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.invalidRequest(message));
	}
}
