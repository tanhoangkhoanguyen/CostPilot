package com.costpilot.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.analytics.dto.ReconciliationResult;
import com.costpilot.analytics.dto.SpendBucket;
import com.costpilot.core.model.Usage;
import com.costpilot.cost.Cost;
import com.costpilot.cost.LedgerContext;
import com.costpilot.cost.UsageLedgerService;

// 5.4 acceptance: ClickHouse aggregations reconcile exactly with the Postgres ledger for
// a fixed window. Seeds matching rows in both stores (no Kafka - this isolates the read
// side) and asserts /reconcile and the spend aggregation. ClickHouse is enabled; Kafka
// stays off so no consumer/producer is needed.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "costpilot.clickhouse.enabled=true")
@Import(TestcontainersConfiguration.class)
@Testcontainers
class AnalyticsReconciliationIT {

	@Container
	static ClickHouseContainer clickhouse = new ClickHouseContainer(
			DockerImageName.parse("clickhouse/clickhouse-server:24-alpine"));

	@DynamicPropertySource
	static void clickhouseProps(DynamicPropertyRegistry registry) {
		registry.add("costpilot.clickhouse.jdbc-url",
				() -> clickhouse.getJdbcUrl().replace("jdbc:clickhouse:", "jdbc:ch:"));
		registry.add("costpilot.clickhouse.username", clickhouse::getUsername);
		registry.add("costpilot.clickhouse.password", clickhouse::getPassword);
	}

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private UsageLedgerService ledger;

	@Autowired
	@Qualifier("clickhouseJdbcTemplate")
	private JdbcTemplate clickhouseJdbc;

	@Autowired
	private com.costpilot.domain.UsageRecordRepository usageRepository;

	private final Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS);
	private final Instant t1 = Instant.now().plus(1, ChronoUnit.HOURS);

	@BeforeEach
	void schema() {
		// reconciliation compares the whole window across all teams, so start from a
		// clean ledger + empty OLAP table for a deterministic total
		usageRepository.deleteAll();
		clickhouseJdbc.execute("create database if not exists costpilot");
		clickhouseJdbc.execute("""
				create table if not exists costpilot.usage_events (
				    event_id UUID, tenant_id String, team_id String, project_id String, user_id String,
				    environment LowCardinality(String), provider LowCardinality(String),
				    original_model LowCardinality(String), executed_model LowCardinality(String),
				    decision LowCardinality(String), finish_reason LowCardinality(String),
				    input_tokens UInt32, output_tokens UInt32, cost_nanos Int64,
				    event_ts DateTime64(3,'UTC'), ingested_at DateTime64(3,'UTC') DEFAULT now64(3))
				engine = ReplacingMergeTree(ingested_at)
				partition by toYYYYMM(event_ts)
				order by (team_id, project_id, event_ts, event_id)
				""");
		clickhouseJdbc.execute("truncate table if exists costpilot.usage_events");
	}

	// insert a ledger row (Postgres) and a matching ClickHouse event with the same cost
	private void seedMatching(String team, long costNanos, int in, int out) {
		BigDecimal cost = BigDecimal.valueOf(costNanos).movePointLeft(9);
		ledger.record(new LedgerContext(null, team, "proj", "user", "prod", "recon-" + UUID.randomUUID()),
				"openai", "gpt-4o-mini", new Usage(in, out),
				new Cost(cost, BigDecimal.ZERO), null);
		clickhouseJdbc.update(
				"insert into costpilot.usage_events (event_id, team_id, project_id, executed_model, decision, "
						+ "finish_reason, input_tokens, output_tokens, cost_nanos, event_ts) "
						+ "values (?,?,?,?,?,?,?,?,?,fromUnixTimestamp64Milli(?))",
				UUID.randomUUID().toString(), team, "proj", "gpt-4o-mini", "allow", "stop",
				in, out, costNanos, Instant.now().toEpochMilli());
	}

	@Test
	void clickHouseTotalsReconcileWithThePostgresLedger() {
		String team = "recon-" + UUID.randomUUID();
		seedMatching(team, 3_000_000L, 100, 50);
		seedMatching(team, 7_000_000L, 200, 80);

		// admin key: reconcile the whole window across all teams (a non-admin would be
		// team-scoped, which is covered separately)
		ReconciliationResult result = restTemplate.exchange(
				"/api/analytics/reconcile?from={f}&to={t}",
				org.springframework.http.HttpMethod.GET,
				new org.springframework.http.HttpEntity<>(com.costpilot.security.AuthTestSupport.admin()),
				ReconciliationResult.class, t0.toString(), t1.toString()).getBody();

		assertThat(result.postgresRows()).isEqualTo(result.clickhouseRows());
		assertThat(result.postgresNanos()).isEqualTo(result.clickhouseNanos());
		assertThat(result.reconciled()).isTrue();
		assertThat(result.clickhouseNanos()).isEqualTo(10_000_000L);
	}

	@Test
	void spendByTeamReturnsDedupedClickHouseTotals() {
		String team = "spend-" + UUID.randomUUID();
		seedMatching(team, 5_000_000L, 10, 5);
		seedMatching(team, 5_000_000L, 10, 5);

		SpendBucket[] buckets = restTemplate.exchange(
				"/api/analytics/spend?groupBy=team&from={f}&to={t}",
				org.springframework.http.HttpMethod.GET,
				new org.springframework.http.HttpEntity<>(com.costpilot.security.AuthTestSupport.admin()),
				SpendBucket[].class, t0.toString(), t1.toString()).getBody();

		SpendBucket bucket = java.util.Arrays.stream(buckets)
				.filter(b -> team.equals(b.key())).findFirst().orElseThrow();
		assertThat(bucket.costUsd()).isEqualTo("0.010000000");
		assertThat(bucket.requests()).isEqualTo(2);
	}
}
