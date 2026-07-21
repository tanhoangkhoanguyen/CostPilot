package com.costpilot.provider.gemini;

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
import com.costpilot.upstream.UpstreamProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Gemini generateContent API: model rides in the URL, roles are user/model,
// system prompt is systemInstruction, streaming is :streamGenerateContent?alt=sse
// with full GenerateContentResponse chunks. Auth is flavor-dependent: the Developer
// API uses x-goog-api-key, Vertex AI uses an ADC OAuth2 bearer token (11.1).
@Component
public class GeminiAdapter implements ProviderAdapter {

	private final ObjectMapper objectMapper;
	private final VertexTokenProvider vertexTokenProvider;

	public GeminiAdapter(ObjectMapper objectMapper, VertexTokenProvider vertexTokenProvider) {
		this.objectMapper = objectMapper;
		this.vertexTokenProvider = vertexTokenProvider;
	}

	@Override
	public String providerId() {
		return "gemini";
	}

	@Override
	public String chatPath(CanonicalChatRequest request, UpstreamProperties.Provider config) {
		String method = request.stream() ? "streamGenerateContent" : "generateContent";
		String path;
		if (config.getFlavor() == UpstreamProperties.Provider.Flavor.VERTEX) {
			// Vertex fully qualifies the URL - host derives from the region so the operator
			// only configures flavor/project/location; base body/parse logic is unchanged.
			path = "https://" + config.getLocation() + "-aiplatform.googleapis.com"
					+ "/v1/projects/" + config.getProject() + "/locations/" + config.getLocation()
					+ "/publishers/google/models/" + request.model() + ":" + method;
		} else {
			path = "/v1beta/models/" + request.model() + ":" + method;
		}
		return request.stream() ? path + "?alt=sse" : path;
	}

	@Override
	public Object buildUpstreamBody(CanonicalChatRequest request) {
		String system = request.messages().stream()
				.filter(m -> "system".equals(m.role()))
				.map(CanonicalChatRequest.Message::content)
				.collect(Collectors.joining("\n"));
		List<Map<String, Object>> contents = new ArrayList<>();
		for (CanonicalChatRequest.Message message : request.messages()) {
			if (!"system".equals(message.role())) {
				contents.add(Map.of(
						"role", "assistant".equals(message.role()) ? "model" : "user",
						"parts", List.of(Map.of("text", message.content()))));
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contents", contents);
		if (!system.isEmpty()) {
			body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
		}
		if (request.maxTokens() != null) {
			body.put("generationConfig", Map.of("maxOutputTokens", request.maxTokens()));
		}
		return body;
	}

	@Override
	public void applyAuth(HttpHeaders headers, UpstreamProperties.Provider config) {
		if (config.getFlavor() == UpstreamProperties.Provider.Flavor.VERTEX) {
			// Vertex: short-lived ADC bearer token, no static api key on the image
			headers.setBearerAuth(vertexTokenProvider.getAccessToken());
			return;
		}
		String apiKey = config.getApiKey();
		if (apiKey != null && !apiKey.isBlank()) {
			headers.set("x-goog-api-key", apiKey);
		}
	}

	@Override
	public CanonicalChatResponse parseResponse(String body) {
		JsonNode root = read(body);
		JsonNode candidate = root.path("candidates").path(0);
		return new CanonicalChatResponse(
				null,
				root.path("modelVersion").asText(null),
				text(candidate),
				mapFinishReason(candidate.path("finishReason").asText(null)),
				usage(root.path("usageMetadata")));
	}

	@Override
	public Optional<CanonicalStreamChunk> parseStreamEvent(String data) {
		JsonNode root = read(data);
		JsonNode candidate = root.path("candidates").path(0);
		String content = text(candidate);
		String finish = mapFinishReason(candidate.path("finishReason").asText(null));
		Usage usage = finish == null ? null : usage(root.path("usageMetadata"));
		if (content.isEmpty() && finish == null) {
			return Optional.empty();
		}
		// gemini has no [DONE] sentinel; the final chunk carries finishReason (and usage)
		return Optional.of(new CanonicalStreamChunk(null, content.isEmpty() ? null : content, finish, usage, false));
	}

	private String text(JsonNode candidate) {
		StringBuilder text = new StringBuilder();
		for (JsonNode part : candidate.path("content").path("parts")) {
			if (part.hasNonNull("text")) {
				text.append(part.get("text").asText());
			}
		}
		return text.toString();
	}

	private Usage usage(JsonNode usageMetadata) {
		if (usageMetadata == null || usageMetadata.isMissingNode()) {
			return null;
		}
		return new Usage(usageMetadata.path("promptTokenCount").asInt(0),
				usageMetadata.path("candidatesTokenCount").asInt(0));
	}

	private String mapFinishReason(String geminiReason) {
		if (geminiReason == null || geminiReason.isBlank()) {
			return null;
		}
		return switch (geminiReason) {
			case "STOP" -> "stop";
			case "MAX_TOKENS" -> "length";
			default -> geminiReason.toLowerCase();
		};
	}

	private JsonNode read(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (Exception e) {
			throw new UpstreamParseException("unparseable gemini payload", e);
		}
	}
}
