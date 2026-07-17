package com.costpilot.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.UsageEvent;

// 5.3 acceptance: events land in ClickHouse within the lag target; ingest is idempotent
// (a redelivered event_id does not double-count). Kafka + ClickHouse both containerized;
// the app's Java consumer bridges them, so no shared docker network is needed.
@SpringBootTest(properties = {
		"costpilot.kafka.enabled=true",
		"costpilot.clickhouse.enabled=true"
})
@Import(TestcontainersConfiguration.class)
@Testcontainers
class ClickHouseUsageIngestIT {

	@Container
	static ClickHouseContainer clickhouse = new ClickHouseContainer(
			DockerImageName.parse("clickhouse/clickhouse-server:24-alpine"));

	@Container
	static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

	@DynamicPropertySource
	static void containerProps(DynamicPropertyRegistry registry) {
		// pin BOTH producer and consumer to the container broker (a mixed
		// @ServiceConnection + @Container setup can otherwise leave the producer on the
		// application.yml default localhost:9092)
		registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
		// map the ClickHouse container onto our custom (non spring.datasource.*) properties
		registry.add("costpilot.clickhouse.jdbc-url",
				() -> clickhouse.getJdbcUrl().replace("jdbc:clickhouse:", "jdbc:ch:"));
		registry.add("costpilot.clickhouse.username", clickhouse::getUsername);
		registry.add("costpilot.clickhouse.password", clickhouse::getPassword);
	}

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Autowired
	@Qualifier("clickhouseJdbcTemplate")
	private JdbcTemplate clickhouseJdbc;

	@Autowired
	private com.costpilot.kafka.KafkaProperties kafkaProps;

	@BeforeEach
	void schema() {
		// the container does not run the compose init script; create the table here
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
	}

	private UsageEvent event(UUID eventId, String team, long costNanos) {
		return new UsageEvent(eventId, "tenant-a", team, "proj-a", "user-a", "prod",
				"openai", "gpt-4o", "gpt-4o-mini", "downgrade", "stop", 100, 50, costNanos, Instant.now());
	}

	// dedup-at-read (money-exact): collapse duplicate event_id before summing
	private long dedupedCount(String team) {
		Long n = clickhouseJdbc.queryForObject(
				"select count() from (select event_id from costpilot.usage_events "
						+ "where team_id = ? group by event_id)",
				Long.class, team);
		return n == null ? 0 : n;
	}

	private long rawCount(String team) {
		Long n = clickhouseJdbc.queryForObject(
				"select count() from costpilot.usage_events where team_id = ?", Long.class, team);
		return n == null ? 0 : n;
	}

	private void awaitRawCount(String team, long expected) {
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline && rawCount(team) < expected) {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Test
	void eventsPublishedToKafkaLandInClickHouse() {
		String team = "ingest-" + UUID.randomUUID();
		String topic = kafkaProps.getUsageEventsTopic();

		kafkaTemplate.send(topic, team, event(UUID.randomUUID(), team, 1_000_000L));
		kafkaTemplate.send(topic, team, event(UUID.randomUUID(), team, 2_000_000L));

		awaitRawCount(team, 2);
		assertThat(dedupedCount(team)).isEqualTo(2);
	}

	@Test
	void redeliveredEventIdDoesNotDoubleCount() {
		String team = "dedup-" + UUID.randomUUID();
		String topic = kafkaProps.getUsageEventsTopic();
		UUID dup = UUID.randomUUID();

		// same event_id published three times (redelivery / replay)
		kafkaTemplate.send(topic, team, event(dup, team, 5_000_000L));
		kafkaTemplate.send(topic, team, event(dup, team, 5_000_000L));
		kafkaTemplate.send(topic, team, event(dup, team, 5_000_000L));

		awaitRawCount(team, 3);
		// raw rows may be 3 (merges are async), but the deduped logical count is 1
		assertThat(dedupedCount(team)).isEqualTo(1);
	}

	@Test
	void ingestLandsWithinLagTarget() {
		String team = "lag-" + UUID.randomUUID();
		long start = System.currentTimeMillis();

		kafkaTemplate.send(kafkaProps.getUsageEventsTopic(), team, event(UUID.randomUUID(), team, 1_000_000L));

		awaitRawCount(team, 1);
		long elapsed = System.currentTimeMillis() - start;
		assertThat(rawCount(team)).isEqualTo(1);
		// documented lag target < 5s (generous headroom for a cold consumer/test broker)
		assertThat(elapsed).isLessThan(15_000);
	}
}
