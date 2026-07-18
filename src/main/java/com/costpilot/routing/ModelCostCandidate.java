package com.costpilot.routing;

import java.util.UUID;

import com.costpilot.cost.Cost;

// One entry of the ranked cheapest-first list (7.1). priceId pins the exact price
// version the estimate was computed with, so a later ledger write for the routed
// model can reuse it instead of re-resolving.
public record ModelCostCandidate(
		String provider,
		String model,
		Cost estimate,
		UUID priceId) {
}
