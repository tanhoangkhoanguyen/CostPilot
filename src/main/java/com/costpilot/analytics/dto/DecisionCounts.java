package com.costpilot.analytics.dto;

// Governance-decision mix over the window (5.4): how often requests were allowed,
// downgraded, cut off mid-stream, denied, or held for approval.
public record DecisionCounts(
		long allow,
		long downgrade,
		long cutoff,
		long deny,
		long approvalRequired) {
}
