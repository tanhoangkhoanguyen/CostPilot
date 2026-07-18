package com.costpilot.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.costpilot.KafkaTestcontainersConfiguration;
import com.costpilot.TestcontainersConfiguration;
import com.costpilot.security.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// 5.2 acceptance: each forwarded request emits exactly one usage event; the event
// carries the decision + requested-vs-executed model + cost; a replay (same idempotency
// key) does NOT emit a second event. Kafka is enabled for this IT only.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "costpilot.kafka.enabled=true")
@Import({ TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class })
class UsageEventIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private KafkaAdmin kafkaAdmin;

	private final ObjectMapper mapper = new ObjectMapper();

	// KafkaAdmin exposes bootstrap.servers as a List; joining avoids the "[host:port]"
	// that List.toString() would produce (which Kafka rejects as an invalid url).
	private String bootstrapServers() {
		Object value = kafkaAdmin.getConfigurationProperties().get("bootstrap.servers");
		if (value instanceof java.util.List<?> list) {
			return list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
		}
		return String.valueOf(value);
	}
	private Consumer<String, String> consumer;

	@AfterEach
	void closeConsumer() {
		if (consumer != null) {
			consumer.close();
		}
	}

	private Consumer<String, String> consumer(String topic) {
		Map<String, Object> props = KafkaTestUtils.consumerProps(
				bootstrapServers(), "it-" + UUID.randomUUID(), "true");
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		Consumer<String, String> c = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
		c.subscribe(List.of(topic));
		return c;
	}

	private ResponseEntity<String> post(String team, String model, boolean stream, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		headers.set("X-Team-ID", team);
		if (idempotencyKey != null) {
			headers.set("Idempotency-Key", idempotencyKey);
		}
		String body = """
				{
				  "model": "%s",
				  "messages": [{"role": "user", "content": "hello costpilot events"}],
				  "stream": %s,
				  "max_tokens": 16
				}
				""".formatted(model, stream);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private List<JsonNode> drain(Consumer<String, String> c, String team) {
		List<JsonNode> events = new java.util.ArrayList<>();
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));
			for (ConsumerRecord<String, String> r : records) {
				try {
					JsonNode node = mapper.readTree(r.value());
					if (team.equals(node.path("teamId").asText())) {
						events.add(node);
					}
				} catch (Exception ignored) {
					// non-JSON record - skip
				}
			}
			if (!events.isEmpty()) {
				// give a beat for any (erroneous) second event to also arrive
				ConsumerRecords<String, String> more = c.poll(Duration.ofMillis(500));
				for (ConsumerRecord<String, String> r : more) {
					try {
						JsonNode node = mapper.readTree(r.value());
						if (team.equals(node.path("teamId").asText())) {
							events.add(node);
						}
					} catch (Exception ignored) {
						// skip
					}
				}
				break;
			}
		}
		return events;
	}

	@Test
	void forwardedRequestEmitsExactlyOneEventWithDecisionAndCost() {
		String team = "evt-allow-" + UUID.randomUUID();
		consumer = consumer("costpilot.usage-events");

		assertThat(post(team, "gpt-4o-mini", false, null).getStatusCode()).isEqualTo(HttpStatus.OK);

		List<JsonNode> events = drain(consumer, team);
		assertThat(events).hasSize(1);
		JsonNode e = events.get(0);
		assertThat(e.get("decision").asText()).isEqualTo("allow");
		assertThat(e.get("originalModel").asText()).isEqualTo("gpt-4o-mini");
		assertThat(e.get("executedModel").asText()).isEqualTo("gpt-4o-mini");
		assertThat(e.get("costNanos").asLong()).isGreaterThan(0);
		assertThat(e.get("eventId").asText()).isNotBlank();
		assertThat(e.get("inputTokens").asInt()).isGreaterThan(0);
	}

	@Test
	void replayWithSameIdempotencyKeyDoesNotEmitASecondEvent() {
		String team = "evt-replay-" + UUID.randomUUID();
		String key = "evt-key-" + UUID.randomUUID();
		consumer = consumer("costpilot.usage-events");

		assertThat(post(team, "gpt-4o-mini", false, key).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(post(team, "gpt-4o-mini", false, key).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(post(team, "gpt-4o-mini", false, key).getStatusCode()).isEqualTo(HttpStatus.OK);

		List<JsonNode> events = drain(consumer, team);
		assertThat(events).hasSize(1);
	}
}
