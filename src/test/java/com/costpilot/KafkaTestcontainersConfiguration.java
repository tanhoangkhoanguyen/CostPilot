package com.costpilot;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// Kafka container is separate from the shared Postgres/Redis config so only the 5.2
// usage-event IT pays the broker startup cost - the ~15 other ITs stay fast.
// @ServiceConnection wires spring.kafka.bootstrap-servers automatically.
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
	}
}
