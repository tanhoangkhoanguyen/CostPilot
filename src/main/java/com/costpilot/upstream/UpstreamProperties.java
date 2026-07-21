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

		/**
		 * Gemini auth/endpoint flavor (11.1). DEVELOPER is the Gemini Developer API
		 * (x-goog-api-key); VERTEX is Vertex AI (OAuth2 bearer via ADC). Ignored by
		 * non-Gemini providers.
		 */
		public enum Flavor {
			DEVELOPER, VERTEX
		}

		/** Base URL used when mode = REAL, e.g. https://api.openai.com */
		private String baseUrl;
		private String apiKey;
		/** Gemini only (11.1): selects the auth strategy - api key vs ADC bearer token. */
		private Flavor flavor = Flavor.DEVELOPER;
		/** Vertex only (11.2): GCP project id, part of the Vertex resource path. */
		private String project;
		/** Vertex only (11.2): GCP region, e.g. us-central1 - part of host and path. */
		private String location;

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

		public Flavor getFlavor() {
			return flavor;
		}

		public void setFlavor(Flavor flavor) {
			this.flavor = flavor;
		}

		public String getProject() {
			return project;
		}

		public void setProject(String project) {
			this.project = project;
		}

		public String getLocation() {
			return location;
		}

		public void setLocation(String location) {
			this.location = location;
		}
	}
}
