package com.costpilot.provider.openai;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.provider.ProviderAdapter;
import com.costpilot.provider.UpstreamParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class OpenAiAdapter implements ProviderAdapter {

	public static final String DONE_MARKER = "[DONE]";

	private final ObjectMapper objectMapper;

	public OpenAiAdapter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String providerId() {
		return "openai";
	}

	@Override
	public String chatPath(CanonicalChatRequest request, com.costpilot.upstream.UpstreamProperties.Provider config) {
		return "/v1/chat/completions";
	}

	@Override
	public Object buildUpstreamBody(CanonicalChatRequest request) {
		List<Map<String, String>> messages = request.messages().stream()
				.map(m -> Map.of("role", m.role(), "content", m.content()))
				.toList();
		if (request.maxTokens() != null) {
			return Map.of(
					"model", request.model(),
					"messages", messages,
					"stream", request.stream(),
					"max_tokens", request.maxTokens());
		}
		return Map.of(
				"model", request.model(),
				"messages", messages,
				"stream", request.stream());
	}

	@Override
	public void applyAuth(HttpHeaders headers, com.costpilot.upstream.UpstreamProperties.Provider config) {
		String apiKey = config.getApiKey();
		if (apiKey != null && !apiKey.isBlank()) {
			headers.setBearerAuth(apiKey);
		}
	}

	@Override
	public CanonicalChatResponse parseResponse(String body) {
		JsonNode root = read(body);
		JsonNode choice = root.path("choices").path(0);
		return new CanonicalChatResponse(
				root.path("id").asText(null),
				root.path("model").asText(null),
				choice.path("message").path("content").asText(""),
				choice.path("finish_reason").asText(null),
				usage(root.path("usage")));
	}

	@Override
	public Optional<CanonicalStreamChunk> parseStreamEvent(String data) {
		if (DONE_MARKER.equals(data.trim())) {
			return Optional.of(CanonicalStreamChunk.endOfStream());
		}
		JsonNode root = read(data);
		JsonNode choice = root.path("choices").path(0);
		JsonNode delta = choice.path("delta");
		if (delta.hasNonNull("content")) {
			return Optional.of(CanonicalStreamChunk.content(delta.get("content").asText()));
		}
		if (delta.hasNonNull("role")) {
			return Optional.of(CanonicalStreamChunk.role(delta.get("role").asText()));
		}
		if (choice.hasNonNull("finish_reason")) {
			return Optional.of(CanonicalStreamChunk.finish(choice.get("finish_reason").asText()));
		}
		if (root.hasNonNull("usage")) {
			return Optional.of(CanonicalStreamChunk.usageOnly(usage(root.get("usage"))));
		}
		return Optional.empty();
	}

	private Usage usage(JsonNode usageNode) {
		if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
			return null;
		}
		return new Usage(usageNode.path("prompt_tokens").asInt(0), usageNode.path("completion_tokens").asInt(0));
	}

	private JsonNode read(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (Exception e) {
			throw new UpstreamParseException("unparseable openai payload", e);
		}
	}
}
