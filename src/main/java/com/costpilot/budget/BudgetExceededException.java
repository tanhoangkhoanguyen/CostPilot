package com.costpilot.budget;

import java.math.BigDecimal;

public class BudgetExceededException extends RuntimeException {

	private final BudgetScope scope;
	private final String scopeRef;
	private final BigDecimal remaining;

	public BudgetExceededException(BudgetScope scope, String scopeRef, BigDecimal remaining) {
		super("budget exhausted for " + scope.dbValue() + "=" + scopeRef
				+ " remaining=" + remaining.toPlainString());
		this.scope = scope;
		this.scopeRef = scopeRef;
		this.remaining = remaining;
	}

	public BudgetScope getScope() {
		return scope;
	}

	public String getScopeRef() {
		return scopeRef;
	}

	public BigDecimal getRemaining() {
		return remaining;
	}
}
