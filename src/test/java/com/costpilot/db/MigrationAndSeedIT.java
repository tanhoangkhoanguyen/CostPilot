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
		// bump this when adding a migration; V13 added prompt_cache + pgvector (10.1)
		assertThat(applied).isEqualTo(13);
		assertThat(failed).isZero();
	}

	@Test
	void seedDataIsLoaded() {
		assertThat(jdbc.queryForObject("select count(*) from tenant", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select name from tenant", String.class)).isEqualTo("acme");
		// V8 adds a second team (research) for per-team isolation demos/tests
		assertThat(jdbc.queryForObject("select count(*) from team", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
				"select name from team where id = '00000000-0000-0000-0000-000000000011'", String.class))
				.isEqualTo("platform");
		assertThat(jdbc.queryForObject(
				"select name from project where id = '00000000-0000-0000-0000-000000000021'", String.class))
				.isEqualTo("chatbot");
		// count only the seeded pairs - other tests sharing this context may add prices
		assertThat(jdbc.queryForObject("""
				select count(*) from model_price where version = 1 and model in
				('gpt-4o','gpt-4o-mini','claude-sonnet-4-5','claude-haiku-4-5','gemini-2.5-pro','gemini-2.5-flash')
				""", Integer.class)).isEqualTo(6);
	}

	@Test
	void pricesAreExactDecimals() {
		BigDecimal input = jdbc.queryForObject(
				"select input_price_per_1k from model_price where provider = 'openai' and model = 'gpt-4o-mini'",
				BigDecimal.class);
		assertThat(input).isEqualByComparingTo("0.00015");
	}

	@Test
	void seededApiKeysAreHashedOnly() {
		// V8 seeds 3 keys (2 team, 1 admin); only hashes are stored, never a raw key
		assertThat(jdbc.queryForObject("select count(*) from api_key", Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from api_key where is_admin", Integer.class)).isEqualTo(1);
		// a seeded hash is a 64-char hex HMAC-SHA256, never the demo raw key text
		String hash = jdbc.queryForObject(
				"select key_hash from api_key where name = 'demo-platform'", String.class);
		assertThat(hash).hasSize(64).matches("[0-9a-f]+");
		assertThat(hash).doesNotContain("cp_demo");
	}
}
