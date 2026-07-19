package com.costpilot.budget;

// Enforcement scopes, matching budget.scope_type and the usage_record column
// each one aggregates over.
public enum BudgetScope {
	TENANT("tenant"),
	TEAM("team"),
	PROJECT("project"),
	MODEL("model");

	private final String dbValue;

	BudgetScope(String dbValue) {
		this.dbValue = dbValue;
	}

	public String dbValue() {
		return dbValue;
	}

	/** Parse the db token (tenant|team|project|model); throws on anything else. */
	public static BudgetScope fromDbValue(String value) {
		for (BudgetScope scope : values()) {
			if (scope.dbValue.equals(value)) {
				return scope;
			}
		}
		throw new IllegalArgumentException("unknown budget scope: " + value);
	}
}
