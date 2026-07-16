package com.costpilot.core.model;

// Token usage extracted from a provider response - the input to all cost math (2.1).
public record Usage(int inputTokens, int outputTokens) {

	public int totalTokens() {
		return inputTokens + outputTokens;
	}
}
