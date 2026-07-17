package com.costpilot.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.costpilot.upstream.UpstreamProperties;

// Discovers every ProviderAdapter bean via DI - adding a provider is implementing
// the interface, nothing here changes. Selection: explicit config mapping first
// (costpilot.upstream.model-providers), then model-id prefix convention.
@Component
public class ProviderRegistry {

	private final Map<String, ProviderAdapter> byId;
	private final UpstreamProperties properties;

	public ProviderRegistry(List<ProviderAdapter> adapters, UpstreamProperties properties) {
		this.byId = adapters.stream()
				.collect(Collectors.toUnmodifiableMap(ProviderAdapter::providerId, Function.identity()));
		this.properties = properties;
	}

	public ProviderAdapter byProviderId(String providerId) {
		ProviderAdapter adapter = byId.get(providerId);
		if (adapter == null) {
			throw new IllegalArgumentException("no adapter for provider: " + providerId);
		}
		return adapter;
	}

	public ProviderAdapter forModel(String model) {
		String configured = properties.getModelProviders().get(model);
		if (configured != null) {
			return byProviderId(configured);
		}
		String m = model == null ? "" : model.toLowerCase();
		if (m.startsWith("claude")) {
			return byProviderId("anthropic");
		}
		if (m.startsWith("gemini")) {
			return byProviderId("gemini");
		}
		return byProviderId("openai");
	}
}
