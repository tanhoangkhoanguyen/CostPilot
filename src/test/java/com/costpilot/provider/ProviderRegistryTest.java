package com.costpilot.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.costpilot.provider.anthropic.AnthropicAdapter;
import com.costpilot.provider.gemini.GeminiAdapter;
import com.costpilot.provider.gemini.VertexTokenProvider;
import com.costpilot.provider.openai.OpenAiAdapter;
import com.costpilot.upstream.UpstreamProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

class ProviderRegistryTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final UpstreamProperties properties = new UpstreamProperties();
	// routing tests never authenticate, so the token source is never invoked
	private final VertexTokenProvider vertexTokens = new VertexTokenProvider(() -> {
		throw new UnsupportedOperationException("vertex auth not exercised in routing tests");
	});
	private final ProviderRegistry registry = new ProviderRegistry(
			List.of(new OpenAiAdapter(mapper), new AnthropicAdapter(mapper),
					new GeminiAdapter(mapper, vertexTokens)),
			properties);

	@Test
	void routesByModelPrefixConvention() {
		assertThat(registry.forModel("gpt-4o-mini").providerId()).isEqualTo("openai");
		assertThat(registry.forModel("claude-sonnet-4-5").providerId()).isEqualTo("anthropic");
		assertThat(registry.forModel("gemini-2.5-flash").providerId()).isEqualTo("gemini");
		assertThat(registry.forModel("some-unknown-model").providerId()).isEqualTo("openai");
	}

	@Test
	void explicitConfigMappingWinsOverConvention() {
		properties.getModelProviders().put("claude-sonnet-4-5", "openai");
		assertThat(registry.forModel("claude-sonnet-4-5").providerId()).isEqualTo("openai");
	}

	@Test
	void unknownProviderIdFailsLoudly() {
		properties.getModelProviders().put("weird-model", "nope");
		assertThatThrownBy(() -> registry.forModel("weird-model"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("nope");
	}
}
