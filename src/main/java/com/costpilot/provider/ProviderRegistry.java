package com.costpilot.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

// Discovers every ProviderAdapter bean via DI - adding a provider is implementing
// the interface, nothing here changes. Model -> provider mapping is a prefix
// heuristic until 1.2 makes it config-driven.
@Component
public class ProviderRegistry {

	private final Map<String, ProviderAdapter> byId;

	public ProviderRegistry(List<ProviderAdapter> adapters) {
		this.byId = adapters.stream()
				.collect(Collectors.toUnmodifiableMap(ProviderAdapter::providerId, Function.identity()));
	}

	public ProviderAdapter byProviderId(String providerId) {
		ProviderAdapter adapter = byId.get(providerId);
		if (adapter == null) {
			throw new IllegalArgumentException("no adapter for provider: " + providerId);
		}
		return adapter;
	}

	public ProviderAdapter forModel(String model) {
		String m = model == null ? "" : model.toLowerCase();
		if (m.startsWith("claude") && byId.containsKey("anthropic")) {
			return byId.get("anthropic");
		}
		if (m.startsWith("gemini") && byId.containsKey("gemini")) {
			return byId.get("gemini");
		}
		return byProviderId("openai");
	}
}
