package com.costpilot.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.security.AuthTestSupport;
import com.costpilot.domain.UsageRecordRepository;

// 5.2 acceptance: Kafka down => request still succeeds. Kafka is enabled but points at
// an unreachable broker with fast-fail producer timeouts; the request must still return
// 200 and the ledger row must still be written (publish is best-effort, off the hot path).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"costpilot.kafka.enabled=true",
		"spring.kafka.bootstrap-servers=localhost:59999",
		"spring.kafka.producer.properties.max.block.ms=1000",
		"spring.kafka.producer.properties.delivery.timeout.ms=2000",
		"spring.kafka.producer.properties.request.timeout.ms=1000",
		"spring.kafka.admin.fail-fast=false"
})
@Import(TestcontainersConfiguration.class)
class KafkaDownDoesNotFailRequestIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private UsageRecordRepository usageRepository;

	@Test
	void requestSucceedsAndIsBilledEvenWhenBrokerIsUnreachable() throws Exception {
		String team = "kafka-down-" + UUID.randomUUID();
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		headers.set("X-Team-ID", team);
		String body = """
				{
				  "model": "gpt-4o-mini",
				  "messages": [{"role": "user", "content": "hello even with kafka down"}],
				  "stream": false,
				  "max_tokens": 16
				}
				""";

		ResponseEntity<String> response = restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);

		// the governance path is unaffected by the dead broker
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		// and the money ledger still recorded the request
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline
				&& usageRepository.findAll().stream().noneMatch(r -> team.equals(r.getTeamId()))) {
			Thread.sleep(100);
		}
		assertThat(usageRepository.findAll().stream().anyMatch(r -> team.equals(r.getTeamId()))).isTrue();
	}
}
