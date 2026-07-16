package com.costpilot.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.costpilot.TestcontainersConfiguration;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.function.Tuple2;

// 1.3 acceptance: tokens flow through incrementally (no whole-response buffering)
// and a client disconnect cancels the upstream call.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StreamingPassthroughIT {

	@LocalServerPort
	private int port;

	// ~30 tokens x 20ms mock pacing = ~600ms of generation to observe timing against
	private static final String BODY = """
			{
			  "model": "gpt-4o-mini",
			  "messages": [{"role": "user", "content": "one two three four five six seven eight nine ten \
			eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty \
			twentyone twentytwo twentythree twentyfour twentyfive twentysix twentyseven twentyeight twentynine thirty"}],
			  "stream": true
			}
			""";

	private Flux<String> streamRequest() {
		return WebClient.create("http://localhost:" + port).post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.bodyValue(BODY)
				.retrieve()
				.bodyToFlux(String.class);
	}

	@Test
	void firstTokenReachesClientBeforeGenerationCompletes() {
		List<Tuple2<Long, String>> timed = streamRequest()
				.timestamp()
				.collectList()
				.block(Duration.ofSeconds(30));

		assertThat(timed).isNotNull();
		assertThat(timed.size()).isGreaterThan(10); // many discrete events, not one blob

		long firstArrival = timed.get(0).getT1();
		long lastArrival = timed.get(timed.size() - 1).getT1();
		long window = lastArrival - firstArrival;

		// buffered-whole-response would deliver everything at ~the same instant;
		// passthrough spreads arrivals across the mock's paced generation (~600ms)
		assertThat(window).isGreaterThan(200);
		assertThat(timed.get(timed.size() - 1).getT2()).isEqualTo("[DONE]");
	}

	@Test
	void clientDisconnectCancelsTheUpstreamCall(CapturedOutput output) throws Exception {
		Disposable subscription = streamRequest().subscribe();
		// let a few tokens flow, then drop the client mid-generation
		Thread.sleep(200);
		subscription.dispose();

		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline
				&& !output.getOut().contains("upstream stream cancelled")) {
			Thread.sleep(100);
		}
		assertThat(output.getOut()).contains("upstream stream cancelled provider=openai model=gpt-4o-mini");
	}
}
