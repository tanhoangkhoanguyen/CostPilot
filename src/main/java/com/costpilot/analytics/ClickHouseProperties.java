package com.costpilot.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

// costpilot.clickhouse.* - connection details for the OLAP sink. Deliberately NOT under
// spring.datasource.* so it never becomes the primary datasource that Flyway/JPA bind to.
@ConfigurationProperties(prefix = "costpilot.clickhouse")
public class ClickHouseProperties {

	private String jdbcUrl = "jdbc:ch://localhost:8123/costpilot";
	private String username = "default";
	private String password = "";
	private String usageEventsTable = "costpilot.usage_events";

	public String getJdbcUrl() {
		return jdbcUrl;
	}

	public void setJdbcUrl(String jdbcUrl) {
		this.jdbcUrl = jdbcUrl;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getUsageEventsTable() {
		return usageEventsTable;
	}

	public void setUsageEventsTable(String usageEventsTable) {
		this.usageEventsTable = usageEventsTable;
	}
}
