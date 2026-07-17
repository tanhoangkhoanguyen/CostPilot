package com.costpilot.analytics.ingest;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

// Batch listener factory for the ClickHouse ingest consumer (5.3): reads records as raw
// JSON strings (the consumer parses them, avoiding JsonDeserializer trusted-package
// config), commits offsets manually per batch (AckMode.MANUAL) so we ack only after the
// ClickHouse insert succeeds - at-least-once, made idempotent by ReplacingMergeTree.
@Configuration
@ConditionalOnProperty(name = { "costpilot.kafka.enabled", "costpilot.clickhouse.enabled" }, havingValue = "true")
public class UsageEventConsumerConfig {

	@Bean
	ConsumerFactory<String, String> usageEventConsumerFactory(KafkaProperties kafkaProperties) {
		Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "costpilot-clickhouse-ingest");
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
		// bounded wait so a partial batch still flushes quickly - keeps ingest lag < 5s
		props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
		return new DefaultKafkaConsumerFactory<>(props);
	}

	@Bean
	ConcurrentKafkaListenerContainerFactory<String, String> usageEventBatchListenerFactory(
			ConsumerFactory<String, String> usageEventConsumerFactory) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(usageEventConsumerFactory);
		factory.setBatchListener(true);
		factory.getContainerProperties().setAckMode(AckMode.MANUAL);
		return factory;
	}
}
