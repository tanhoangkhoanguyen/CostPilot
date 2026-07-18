package com.costpilot.analytics.dto;

// Budget utilization: Postgres holds the limit (money truth), ClickHouse the spend
// (reporting), joined in Java. utilization = spent / limit (null when limit is 0).
public record BudgetUtilization(
		String scopeRef,
		String limitUsd,
		String spentUsd,
		Double utilization) {
}
