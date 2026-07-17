package com.costpilot.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 5.2 Kafka wiring. Guarded by costpilot.kafka.enabled so dev and tests that don't run a
// broker still boot (the default is off; docker-compose / real deploys flip it on). The
// producer factory, serializers and topic names come from Spring Boot's Kafka
// auto-config + KafkaProperties; we only add our typed config-props and a topic bean.
@Configuration
@EnableConfigurationProperties(KafkaProperties.class)
@ConditionalOnProperty(name = "costpilot.kafka.enabled", havingValue = "true")
public class KafkaConfig {

	// Spring Kafka auto-creates topics declared as NewTopic beans on a broker that
	// allows it; harmless when the topic already exists.
	@Bean
	org.apache.kafka.clients.admin.NewTopic usageEventsTopic(KafkaProperties props) {
		return new org.apache.kafka.clients.admin.NewTopic(props.getUsageEventsTopic(), 1, (short) 1);
	}

	@Bean
	org.apache.kafka.clients.admin.NewTopic usageEventsDlqTopic(KafkaProperties props) {
		return new org.apache.kafka.clients.admin.NewTopic(props.getDlqTopic(), 1, (short) 1);
	}
}
