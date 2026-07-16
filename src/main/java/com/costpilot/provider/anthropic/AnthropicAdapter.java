package com.costpilot.provider.anthropic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

// Anthropic Messages API: system prompt is a top-level field, max_tokens is
// required, auth is x-api-key + anthropic-version, SSE events are typed objects.
@Component
public class AnthropicAdapter implements ProviderAdapter {

	static final int DEFAULT_MAX_TOKENS = 1024;
	static final String ANTHROPIC_VERSION = "2023-06-01";

	private final ObjectMapper objectMapper;

	public AnthropicAdapter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public String providerId() {
		return "anthropic";
	}

	@Override
	public String chatPath(CanonicalChatRequest request) {
		return "/v1/messages";
	}

	@Override
	public Object buildUpstreamBody(CanonicalChatRequest request) {
		String system = request.messages().stream()
				.filter(m -> "system".equals(m.role()))
				.map(CanonicalChatRequest.Message::content)
				.collect(Collectors.joining("\n"));
		List<Map<String, String>> messages = new ArrayList<>();
		for (CanonicalChatRequest.Message message : request.messages()) {
			if (!"system".equals(message.role())) {
				messages.add(Map.of("role", message.role(), "content", message.content()));
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", request.model());
		body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS);
		body.put("messages", messages);
		if (!system.isEmpty()) {
			body.put("system", system);
		}
		if (request.stream()) {
			body.put("stream", true);
		}
		return body;
	}

	@Override
	public void applyAuth(HttpHeaders headers, String apiKey) {
		headers.set("anthropic-version", ANTHROPIC_VERSION);
		if (apiKey != null && !apiKey.isBlank()) {
			headers.set("x-api-key", apiKey);
		}
	}

	@Override
	public CanonicalChatResponse parseResponse(String body) {
		JsonNode root = read(body);
		StringBuilder content = new StringBuilder();
		for (JsonNode block : root.path("content")) {
			if ("text".equals(block.path("type").asText())) {
				content.append(block.path("text").asText());
			}
		}
		JsonNode usage = root.path("usage");
		return new CanonicalChatResponse(
				root.path("id").asText(null),
				root.path("model").asText(null),
				content.toString(),
				mapStopReason(root.path("stop_reason").asText(null)),
				usage.isMissingNode() ? null
						: new Usage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0)));
	}

	@Override
	public Optional<CanonicalStreamChunk> parseStreamEvent(String data) {
		JsonNode root = read(data);
		String type = root.path("type").asText("");
		return switch (type) {
			case "message_start" -> Optional.of(CanonicalStreamChunk.role("assistant"));
			case "content_block_delta" -> {
				JsonNode delta = root.path("delta");
				yield "text_delta".equals(delta.path("type").asText())
						? Optional.of(CanonicalStreamChunk.content(delta.path("text").asText()))
						: Optional.empty();
			}
			case "message_delta" -> {
				String stop = mapStopReason(root.path("delta").path("stop_reason").asText(null));
				JsonNode usage = root.path("usage");
				Usage u = usage.isMissingNode() ? null : new Usage(0, usage.path("output_tokens").asInt(0));
				yield Optional.of(new CanonicalStreamChunk(null, null, stop, u, false));
			}
			case "message_stop" -> Optional.of(CanonicalStreamChunk.endOfStream());
			default -> Optional.empty();
		};
	}

	private String mapStopReason(String anthropicReason) {
		if (anthropicReason == null) {
			return null;
		}
		return switch (anthropicReason) {
			case "end_turn", "stop_sequence" -> "stop";
			case "max_tokens" -> "length";
			default -> anthropicReason;
		};
	}

	private JsonNode read(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (Exception e) {
			throw new UpstreamParseException("unparseable anthropic payload", e);
		}
	}
}
