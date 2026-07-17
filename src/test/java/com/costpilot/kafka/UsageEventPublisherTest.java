package com.costpilot.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.costpilot.core.model.UsageEvent;

class UsageEventPublisherTest {

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

	private UsageEventPublisher publisher;

	@BeforeEach
	void setUp() {
		KafkaProperties props = new KafkaProperties();
		props.setUsageEventsTopic("costpilot.usage-events");
		props.setDlqTopic("costpilot.usage-events.dlq");
		publisher = new UsageEventPublisher(template, props);
	}

	private UsageEvent event() {
		return new UsageEvent(UUID.randomUUID(), null, "team-a", "proj-a", "user-a", "prod",
				"openai", "gpt-4o", "gpt-4o-mini", "downgrade", "stop", 100, 50, 3_000_000L, Instant.now());
	}

	@Test
	void publishesToTheUsageTopicKeyedByTeam() {
		when(template.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		publisher.publish(event());

		verify(template, timeout(2000)).send(eq("costpilot.usage-events"), eq("team-a"), any(UsageEvent.class));
	}

	@Test
	void sendFailureDeadLettersAndNeverThrows() {
		// main topic fails; DLQ send succeeds
		when(template.send(eq("costpilot.usage-events"), any(), any()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
		when(template.send(eq("costpilot.usage-events.dlq"), any(), any()))
				.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();

		verify(template, timeout(2000)).send(eq("costpilot.usage-events.dlq"), eq("team-a"), any(UsageEvent.class));
	}

	@Test
	void synchronousEnqueueFailureIsSwallowedAndDeadLettered() {
		// send() itself throws (e.g. serialization) before returning a future
		when(template.send(eq("costpilot.usage-events"), any(), any()))
				.thenThrow(new RuntimeException("serialization boom"));
		when(template.send(eq("costpilot.usage-events.dlq"), any(), any()))
				.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();

		verify(template, timeout(2000)).send(eq("costpilot.usage-events.dlq"), any(), any(UsageEvent.class));
	}

	@Test
	void dlqFailureIsSwallowedToo() {
		when(template.send(any(), any(), any()))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("everything down")));

		assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();

		// one attempt to main topic, one to DLQ
		verify(template, timeout(2000).times(2)).send(any(), any(), any(UsageEvent.class));
	}
}
