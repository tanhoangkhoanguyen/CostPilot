package com.costpilot.kafka;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.costpilot.core.model.UsageEvent;

import jakarta.annotation.PreDestroy;

// 5.2: publishes usage events to Kafka, best-effort and off the hot path. The contract
// is that publishing MUST NOT fail the request or the ledger - so publish() never
// throws. send() is asynchronous (fire-and-forget); the request thread only enqueues.
//
// Reliability without a transactional outbox: on send failure the callback re-publishes
// to a dead-letter topic and logs; if even the DLQ send fails we log and drop. Kafka
// being down therefore leaves the request succeeding and the failure recorded, matching
// the acceptance criteria. Present only when costpilot.kafka.enabled=true; callers guard
// via ObjectProvider so dev/tests without a broker still run.
@Component
@ConditionalOnProperty(name = "costpilot.kafka.enabled", havingValue = "true")
public class UsageEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(UsageEventPublisher.class);

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final KafkaProperties properties;
	// KafkaTemplate.send() can block on the first metadata fetch (up to max.block.ms),
	// e.g. when the broker is unreachable. The request runs the ledger write on a virtual
	// thread, so we hand the publish to a dedicated single-thread executor to guarantee
	// the hot path is never blocked by Kafka being slow or down (5.2: latency unchanged).
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "usage-event-publisher");
		t.setDaemon(true);
		return t;
	});

	public UsageEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, KafkaProperties properties) {
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
	}

	public void publish(UsageEvent event) {
		// never block the caller: enqueue and return immediately
		executor.execute(() -> doPublish(event));
	}

	private void doPublish(UsageEvent event) {
		try {
			// key by team so a team's events keep per-partition order and spread across
			// partitions; the event id in the payload is what dedups downstream (5.3)
			String key = event.teamId() != null ? event.teamId() : event.eventId().toString();
			kafkaTemplate.send(properties.getUsageEventsTopic(), key, event)
					.whenComplete((result, ex) -> {
						if (ex != null) {
							deadLetter(event, ex);
						}
					});
		} catch (Exception e) {
			// even enqueueing failed (e.g. serialization) - never propagate to the caller
			deadLetter(event, e);
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}

	private void deadLetter(UsageEvent event, Throwable cause) {
		log.warn("usage event publish failed eventId={} -> dead-lettering: {}",
				event.eventId(), cause.toString());
		try {
			kafkaTemplate.send(properties.getDlqTopic(),
					event.teamId() != null ? event.teamId() : event.eventId().toString(), event)
					.whenComplete((result, ex) -> {
						if (ex != null) {
							log.error("usage event DLQ publish also failed eventId={} - event dropped: {}",
									event.eventId(), ex.toString());
						}
					});
		} catch (Exception e) {
			log.error("usage event DLQ enqueue failed eventId={} - event dropped: {}",
					event.eventId(), e.toString());
		}
	}
}
