package com.costpilot.upstream;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Selects the upstream the gateway forwards to. MOCK (default) targets the embedded
// mock LLM server so dev and tests cost $0; REAL targets the configured provider
// endpoints - switching is env/config only, never a code change.
@ConfigurationProperties(prefix = "costpilot.upstream")
public class UpstreamProperties {

	public enum Mode {
		MOCK, REAL
	}

	private Mode mode = Mode.MOCK;

	/** Per-provider endpoint config, keyed by provider id (openai/anthropic/gemini). */
	private final Map<String, Provider> providers = new HashMap<>();

	/** Explicit model -> provider overrides, e.g. "my-finetune: openai". */
	private final Map<String, String> modelProviders = new HashMap<>();

	public Mode getMode() {
		return mode;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public Map<String, Provider> getProviders() {
		return providers;
	}

	public Map<String, String> getModelProviders() {
		return modelProviders;
	}

	public Provider provider(String providerId) {
		return providers.getOrDefault(providerId, new Provider());
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
