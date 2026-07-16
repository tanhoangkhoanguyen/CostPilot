package com.costpilot.cost;

import java.math.BigDecimal;

// Exact, unrounded money. Callers decide presentation rounding; the ledger stores exact.
public record Cost(BigDecimal inputCost, BigDecimal outputCost) {

	public BigDecimal total() {
		return inputCost.add(outputCost);
	}
}
