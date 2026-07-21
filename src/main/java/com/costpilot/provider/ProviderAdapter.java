package com.costpilot.provider;

import java.util.Optional;

import org.springframework.http.HttpHeaders;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.upstream.UpstreamProperties;

/**
 * The single extension point for LLM providers. Adding a provider means
 * implementing this interface as a Spring bean - the registry discovers it via
 * DI; no controller or service edits.
 */
public interface ProviderAdapter {

	/** Stable provider id, e.g. "openai". Also the mock server path segment. */
	String providerId();

	/** Path of the chat endpoint relative to the provider base URL. */
	String chatPath(CanonicalChatRequest request);

	/** Map the canonical request to the provider's wire format. */
	Object buildUpstreamBody(CanonicalChatRequest request);

	/**
	 * Apply provider-specific auth headers from the resolved provider config. The seam
	 * takes the whole config (not just an api key) so a provider can choose its auth
	 * strategy - e.g. Vertex applies an ADC bearer token while the Gemini Developer API
	 * uses x-goog-api-key (11.1). No-op when no credential is available.
	 */
	void applyAuth(HttpHeaders headers, UpstreamProperties.Provider config);

	/** Parse a provider non-streaming response body (JSON) to canonical, incl. usage. */
	CanonicalChatResponse parseResponse(String body);

	/**
	 * Parse one SSE data payload to a canonical chunk. Empty means the event
	 * carries nothing the gateway acts on (keep-alives, unknown event types).
	 */
	Optional<CanonicalStreamChunk> parseStreamEvent(String data);
}
