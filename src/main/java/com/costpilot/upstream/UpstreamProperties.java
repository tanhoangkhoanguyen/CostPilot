package com.costpilot.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Selects the upstream the gateway forwards to. MOCK (default) targets the embedded
// mock LLM server so dev and tests cost $0; REAL targets the configured provider URL.
@ConfigurationProperties(prefix = "costpilot.upstream")
public class UpstreamProperties {

	public enum Mode {
		MOCK, REAL
	}

	private Mode mode = Mode.MOCK;

	private final Provider openai = new Provider();

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public Provider getOpenai() {
		return openai;
	}

	public static class Provider {
		/** Base URL used when mode = REAL, e.g. https://api.openai.com */
		private String baseUrl;
		private String apiKey;

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}
	}
}
