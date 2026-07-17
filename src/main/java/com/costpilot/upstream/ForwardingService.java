package com.costpilot.upstream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.costpilot.api.dto.ChatCompletionRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ForwardingService {

	private final UpstreamProperties properties;
	private final WebClient webClient;
	private final ObjectProvider<WebServerApplicationContext> webServerContext;

	public ForwardingService(UpstreamProperties properties, WebClient.Builder webClientBuilder,
			ObjectProvider<WebServerApplicationContext> webServerContext) {
		this.properties = properties;
		this.webClient = webClientBuilder.build();
		this.webServerContext = webServerContext;
	}

	public Mono<String> forward(ChatCompletionRequest request) {
		return requestSpec(request)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.bodyToMono(String.class);
	}

	public Flux<String> forwardStream(ChatCompletionRequest request) {
		return requestSpec(request)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.retrieve()
				.bodyToFlux(String.class);
	}

	private WebClient.RequestHeadersSpec<?> requestSpec(ChatCompletionRequest request) {
		WebClient.RequestBodySpec spec = webClient.post()
				.uri(chatCompletionsUrl())
				.contentType(MediaType.APPLICATION_JSON);
		if (properties.getMode() == UpstreamProperties.Mode.REAL
				&& properties.getOpenai().getApiKey() != null) {
			spec.headers(headers -> headers.setBearerAuth(properties.getOpenai().getApiKey()));
		}
		return spec.bodyValue(request);
	}

	private String chatCompletionsUrl() {
		if (properties.getMode() == UpstreamProperties.Mode.REAL) {
			return properties.getOpenai().getBaseUrl() + "/v1/chat/completions";
		}
		// MOCK: the embedded mock server lives in this same app; resolve the live port
		// so it works for bootRun (8080) and random-port tests alike.
		int port = webServerContext.getObject().getWebServer().getPort();
		return "http://localhost:" + port + "/mock/openai/v1/chat/completions";
	}
}
