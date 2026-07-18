package com.costpilot.analytics.dto;

// Spend rolled up by one dimension (team / project / model). costUsd is derived from
// integer nanodollars at query time.
public record SpendBucket(
		String key,
		String costUsd,
		long requests,
		long inputTokens,
		long outputTokens) {
}
