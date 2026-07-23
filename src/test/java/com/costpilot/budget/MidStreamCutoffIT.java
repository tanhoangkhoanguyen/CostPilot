package com.costpilot.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
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
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.domain.UsageRecord;
import com.costpilot.domain.UsageRecordRepository;

/**
 * 4.3 - the headline claim. A generation that would overrun the budget is cut
 * off mid-stream: upstream cancelled, clean truncation signal to the client
 * (finish_reason budget_cutoff, then [DONE]), partial usage + cost in the ledger,
 * total spend bounded by the allowance within one token (the check runs per chunk).
 *
 * Setup: no max_tokens, so the reservation assumes the 1024-token default output,
 * while the mock will actually stream ~2000 tokens - the exact under-estimate
 * scenario cutoff exists for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "costpilot.mock-upstream.token-delay-ms=1")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MidStreamCutoffIT {

	@LocalServerPort
	private int port;

	@Autowired
	private BudgetRepository budgets;

	@Autowired
	private UsageRecordRepository usageRepository;

	private static final int WORDS = 2000;
	private static final BigDecimal CAP = new BigDecimal("0.0013");
	// The overshoot bound is one streamed CHUNK, not one token (12.1): the meter now
	// estimates tokens from content length (~4 chars/token) so the cutoff fires correctly
	// on providers that pack many tokens per chunk. The mock's crossing chunk is "lorem "
	// (6 chars ~= 1.5 tokens on gpt-4o-mini), so cost lands within ~2 output tokens of the
	// cap. This is the honest bound - the earlier sub-token figure was an artifact of the
	// mock emitting exactly one token per chunk.
	private static final BigDecimal ONE_CHUNK = new BigDecimal("0.0000012"); // ~2 output tokens

	@Test
	void generationIsCutOffCleanlyTheInstantItWouldBreachBudget(CapturedOutput output) throws Exception {
		String team = "cutoff-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, CAP));

		String content = "lorem ".repeat(WORDS).trim();
		String body = """
				{
				  "model": "gpt-4o-mini",
				  "messages": [{"role": "user", "content": "%s"}],
				  "stream": true
				}
				""".formatted(content);

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
				.block(Duration.ofSeconds(60)); // flux completes -> no dangling connection

		// clean truncation signal: a finish_reason chunk, then [DONE], as the last events
		assertThat(events).isNotEmpty();
		assertThat(events.get(events.size() - 1)).isEqualTo("[DONE]");
		assertThat(events.get(events.size() - 2)).contains("\"finish_reason\":\"budget_cutoff\"");

		// the stream stopped well before the ~2000-token generation finished
		long contentChunks = events.stream().filter(e -> e.contains("\"content\":\"lorem")).count();
		assertThat(contentChunks).isLessThan(WORDS - 100);
		assertThat(contentChunks).isGreaterThan(0);

		assertThat(output.getOut()).contains("mid-stream cutoff model=gpt-4o-mini");

		// partial usage + cost recorded, bounded by the allowance within one token
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline
				&& usageRepository.findAll().stream().noneMatch(r -> team.equals(r.getTeamId()))) {
			Thread.sleep(100);
		}
		UsageRecord row = usageRepository.findAll().stream()
				.filter(r -> team.equals(r.getTeamId())).findFirst().orElseThrow();
		assertThat(row.getOutputTokens()).isGreaterThan(0);
		assertThat(row.getOutputTokens()).isLessThan(WORDS);
		assertThat(row.getCost()).isGreaterThan(BigDecimal.ZERO);
		assertThat(row.getCost()).isLessThanOrEqualTo(CAP.add(ONE_CHUNK));
	}

	@Test
	void wellWithinBudgetStreamsRunToCompletionUntouched() throws Exception {
		String team = "no-cutoff-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, new BigDecimal("10")));

		String body = """
				{
				  "model": "gpt-4o-mini",
				  "messages": [{"role": "user", "content": "one two three four five"}],
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
				.block(Duration.ofSeconds(30));

		String all = String.join("\n", events);
		assertThat(all).doesNotContain("budget_cutoff");
		assertThat(all).contains("\"finish_reason\":\"stop\"");
		assertThat(events.get(events.size() - 1)).isEqualTo("[DONE]");
	}
}
