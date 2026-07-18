package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.security.AuthTestSupport;
import com.costpilot.domain.UsageRecord;
import com.costpilot.domain.UsageRecordRepository;

// 4.2 acceptance: the running meter's value at stream end equals the ledger cost,
// and metering preserves streaming (no full-buffering).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MidStreamMeteringIT {

	@LocalServerPort
	private int port;

	@Autowired
	private UsageRecordRepository usageRepository;

	@Test
	void runningCostAtStreamEndEqualsTheLedgerCost(CapturedOutput output) throws Exception {
		String team = "meter-" + UUID.randomUUID();
		String body = """
				{
				  "model": "gpt-4o-mini",
				  "messages": [{"role": "user", "content": "one two three four five six seven eight nine ten"}],
				  "stream": true
				}
				""";

		List<String> events = WebClient.create("http://localhost:" + port).post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.header("X-Team-ID", team)
				.header("Authorization", "Bearer " + AuthTestSupport.ADMIN_KEY)
				.bodyValue(body)
				.retrieve()
				.bodyToFlux(String.class)
				.collectList()
				.block(java.time.Duration.ofSeconds(30));

		assertThat(events).isNotEmpty();
		assertThat(events.get(events.size() - 1)).isEqualTo("[DONE]");
		// metering must not collapse the stream into one blob
		assertThat(events.size()).isGreaterThan(5);

		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline
				&& usageRepository.findAll().stream().noneMatch(r -> team.equals(r.getTeamId()))) {
			Thread.sleep(100);
		}
		UsageRecord row = usageRepository.findAll().stream()
				.filter(r -> team.equals(r.getTeamId())).findFirst().orElseThrow();

		// the meter's final log line and the ledger row carry the same number
		assertThat(output.getOut()).contains("stream meter final model=gpt-4o-mini");
		assertThat(output.getOut()).contains("cost=" + row.getCost().stripTrailingZeros().toPlainString());
		assertThat(row.getOutputTokens()).isGreaterThan(0);
		assertThat(row.getInputTokens()).isGreaterThan(0);
	}
}
