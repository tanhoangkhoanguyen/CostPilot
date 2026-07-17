package com.costpilot.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

// costpilot.kafka.* - our topic names and the feature flag. Broker connection itself is
// standard spring.kafka.* (bootstrap-servers, serializers).
@ConfigurationProperties(prefix = "costpilot.kafka")
public class KafkaProperties {

	private boolean enabled = false;
	private String usageEventsTopic = "costpilot.usage-events";
	private String dlqTopic = "costpilot.usage-events.dlq";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getUsageEventsTopic() {
		return usageEventsTopic;
	}

	public void setUsageEventsTopic(String usageEventsTopic) {
		this.usageEventsTopic = usageEventsTopic;
	}

	public String getDlqTopic() {
		return dlqTopic;
	}

	public void setDlqTopic(String dlqTopic) {
		this.dlqTopic = dlqTopic;
	}
}
