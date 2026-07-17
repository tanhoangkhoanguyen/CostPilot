package com.costpilot.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.costpilot.TestcontainersConfiguration;

// Boots the app against an empty Testcontainers Postgres: Flyway must migrate clean
// and Hibernate ddl-auto=validate must accept the schema for the context to load at all.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationAndSeedIT {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void migrationsRunCleanOnEmptyDatabase() {
		Integer applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where success = true", Integer.class);
		Integer failed = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where success = false", Integer.class);
		assertThat(applied).isEqualTo(3);
		assertThat(failed).isZero();
	}

	@Test
	void seedDataIsLoaded() {
		assertThat(jdbc.queryForObject("select count(*) from tenant", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select name from tenant", String.class)).isEqualTo("acme");
		assertThat(jdbc.queryForObject("select name from team", String.class)).isEqualTo("platform");
		assertThat(jdbc.queryForObject("select name from project", String.class)).isEqualTo("chatbot");
		assertThat(jdbc.queryForObject("select count(*) from model_price", Integer.class)).isEqualTo(6);
	}

	@Test
	void pricesAreExactDecimals() {
		BigDecimal input = jdbc.queryForObject(
				"select input_price_per_1k from model_price where provider = 'openai' and model = 'gpt-4o-mini'",
				BigDecimal.class);
		assertThat(input).isEqualByComparingTo("0.00015");
	}

	@Test
	void apiKeyTableExistsAndIsEmpty() {
		assertThat(jdbc.queryForObject("select count(*) from api_key", Integer.class)).isZero();
	}
}
