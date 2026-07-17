package com.costpilot.analytics.ingest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.costpilot.analytics.ClickHouseProperties;
import com.costpilot.core.model.UsageEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;

// 5.3: consumes usage events from Kafka and batch-inserts them into ClickHouse. A Java
// consumer (not a ClickHouse Kafka-engine table) so we control batching, dedup and
// poison-message handling explicitly, and so the pipeline is testable with plain
// Testcontainers (Kafka + ClickHouse bridged by this JVM, no shared docker network).
//
// At-least-once: manual BATCH ack after the insert succeeds. ReplacingMergeTree(event_id)
// makes a redelivered batch idempotent at the row level. A record that won't parse is
// routed to the DLQ and skipped, so one bad message can't stall the partition.
//
// Enabled only when both Kafka and ClickHouse are on.
@Component
@ConditionalOnProperty(name = { "costpilot.kafka.enabled", "costpilot.clickhouse.enabled" }, havingValue = "true")
public class UsageEventConsumer {

	private static final Logger log = LoggerFactory.getLogger(UsageEventConsumer.class);

	private final JdbcTemplate clickhouse;
	private final ClickHouseProperties chProps;
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final com.costpilot.kafka.KafkaProperties kafkaProps;
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	public UsageEventConsumer(JdbcTemplate clickhouseJdbcTemplate, ClickHouseProperties chProps,
			KafkaTemplate<String, Object> kafkaTemplate, com.costpilot.kafka.KafkaProperties kafkaProps) {
		this.clickhouse = clickhouseJdbcTemplate;
		this.chProps = chProps;
		this.kafkaTemplate = kafkaTemplate;
		this.kafkaProps = kafkaProps;
	}

	@KafkaListener(
			topics = "${costpilot.kafka.usage-events-topic}",
			groupId = "costpilot-clickhouse-ingest",
			batch = "true",
			containerFactory = "usageEventBatchListenerFactory")
	public void ingest(List<String> payloads, Acknowledgment ack) {
		List<UsageEvent> events = new java.util.ArrayList<>(payloads.size());
		for (String payload : payloads) {
			try {
				events.add(mapper.readValue(payload, UsageEvent.class));
			} catch (Exception e) {
				// poison message: DLQ + skip so the partition keeps flowing
				log.error("unparseable usage event -> DLQ: {}", e.toString());
				kafkaTemplate.send(kafkaProps.getDlqTopic(), payload);
			}
		}
		if (events.isEmpty()) {
			ack.acknowledge();
			return;
		}
		insertBatch(events);
		// ack only after the insert succeeded: a crash before this re-delivers the batch,
		// and ReplacingMergeTree collapses the duplicate event_ids on merge
		ack.acknowledge();
		log.debug("ingested {} usage events into ClickHouse", events.size());
	}

	private void insertBatch(List<UsageEvent> events) {
		String sql = "insert into " + chProps.getUsageEventsTable() + " "
				+ "(event_id, tenant_id, team_id, project_id, user_id, environment, provider, "
				+ "original_model, executed_model, decision, finish_reason, input_tokens, output_tokens, "
				+ "cost_nanos, event_ts) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
		clickhouse.batchUpdate(sql, events, events.size(), (ps, e) -> {
			ps.setString(1, e.eventId().toString());
			ps.setString(2, nullToEmpty(e.tenantId()));
			ps.setString(3, nullToEmpty(e.teamId()));
			ps.setString(4, nullToEmpty(e.projectId()));
			ps.setString(5, nullToEmpty(e.userId()));
			ps.setString(6, nullToEmpty(e.environment()));
			ps.setString(7, nullToEmpty(e.provider()));
			ps.setString(8, nullToEmpty(e.originalModel()));
			ps.setString(9, nullToEmpty(e.executedModel()));
			ps.setString(10, nullToEmpty(e.decision()));
			ps.setString(11, nullToEmpty(e.finishReason()));
			ps.setInt(12, e.inputTokens());
			ps.setInt(13, e.outputTokens());
			ps.setLong(14, e.costNanos());
			ps.setTimestamp(15, Timestamp.from(e.timestamp() != null ? e.timestamp() : Instant.now()));
		});
	}

	// ClickHouse String columns are non-nullable here; map null identity fields to ''
	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
