package com.costpilot.upstream;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.cost.Cost;
import com.costpilot.cost.CostService;
import com.costpilot.cost.LedgerContext;
import com.costpilot.cost.PriceNotFoundException;
import com.costpilot.cost.UsageLedgerService;
import com.costpilot.provider.ProviderAdapter;
import com.costpilot.provider.ProviderRegistry;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// Provider-agnostic forwarding: resolves the adapter for the model, lets it build
// the wire request and parse the response back to the canonical model.
@Service
public class ForwardingService {

	private static final Logger log = LoggerFactory.getLogger(ForwardingService.class);

	private final UpstreamProperties properties;
	private final ProviderRegistry registry;
	private final CostService costService;
	private final UsageLedgerService usageLedger;
	private final WebClient webClient;
	private final ObjectProvider<WebServerApplicationContext> webServerContext;

	public ForwardingService(UpstreamProperties properties, ProviderRegistry registry,
			CostService costService, UsageLedgerService usageLedger, WebClient.Builder webClientBuilder,
			ObjectProvider<WebServerApplicationContext> webServerContext) {
		this.properties = properties;
		this.registry = registry;
		this.costService = costService;
		this.usageLedger = usageLedger;
		this.webClient = webClientBuilder.build();
		this.webServerContext = webServerContext;
	}

	public Mono<CanonicalChatResponse> forward(CanonicalChatRequest request, LedgerContext ledgerContext) {
		ProviderAdapter adapter = registry.forModel(request.model());
		Instant requestedAt = Instant.now();
		return exchange(adapter, request)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.bodyToMono(String.class)
				.map(adapter::parseResponse)
				.doOnNext(response -> ledger(adapter, request, ledgerContext, response.usage(), requestedAt));
	}

	private void ledger(ProviderAdapter adapter, CanonicalChatRequest request, LedgerContext ledgerContext,
			Usage usage, Instant requestedAt) {
		if (usage == null) {
			return;
		}
		try {
			CostService.Priced priced = costService.pricedCostFor(
					adapter.providerId(), request.model(), usage, requestedAt);
			usageLedger.record(ledgerContext, adapter.providerId(), request.model(), usage,
					priced.cost(), priced.price().getId());
		} catch (PriceNotFoundException e) {
			// unpriced model: nothing to ledger yet; the request itself must not
			// fail over missing price data at this stage
			log.warn("skipping ledger write: {}", e.getMessage());
		}
	}

	public Flux<CanonicalStreamChunk> forwardStream(CanonicalChatRequest request, LedgerContext ledgerContext) {
		ProviderAdapter adapter = registry.forModel(request.model());
		Instant requestedAt = Instant.now();
		// providers report stream usage in pieces (anthropic: input at message_start,
		// output at message_delta; openai/gemini: totals near the end) - keep the
		// max seen per side and write the ledger once, when the stream finishes
		AtomicInteger maxInput = new AtomicInteger();
		AtomicInteger maxOutput = new AtomicInteger();
		return exchange(adapter, request)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.retrieve()
				.bodyToFlux(String.class)
				.flatMap(data -> adapter.parseStreamEvent(data).map(Flux::just).orElseGet(Flux::empty))
				.doOnNext(chunk -> {
					if (chunk.usage() != null) {
						maxInput.accumulateAndGet(chunk.usage().inputTokens(), Math::max);
						maxOutput.accumulateAndGet(chunk.usage().outputTokens(), Math::max);
					}
				})
				// doFinally, not doOnComplete: the gateway disposes this subscription as
				// soon as it relays [DONE], which cancels the flux before onComplete can
				// fire. Whatever ends the stream - complete, cancel, error - if the
				// provider reported usage, those tokens were consumed and must be billed.
				.doFinally(signal -> {
					if (maxInput.get() > 0 || maxOutput.get() > 0) {
						ledger(adapter, request, ledgerContext,
								new Usage(maxInput.get(), maxOutput.get()), requestedAt);
					}
				})
				// disposal propagates here when the client disconnects mid-stream;
				// cancelling the flux tears down the upstream HTTP connection
				.doOnCancel(() -> log.info("upstream stream cancelled provider={} model={}",
						adapter.providerId(), request.model()));
	}

	private WebClient.RequestHeadersSpec<?> exchange(ProviderAdapter adapter, CanonicalChatRequest request) {
		WebClient.RequestBodySpec spec = webClient.post()
				.uri(baseUrl(adapter) + adapter.chatPath(request))
				.contentType(MediaType.APPLICATION_JSON);
		if (properties.getMode() == UpstreamProperties.Mode.REAL) {
			spec.headers(headers -> adapter.applyAuth(headers,
					properties.provider(adapter.providerId()).getApiKey()));
		}
		return spec.bodyValue(adapter.buildUpstreamBody(request));
	}

	private String baseUrl(ProviderAdapter adapter) {
		if (properties.getMode() == UpstreamProperties.Mode.REAL) {
			return properties.provider(adapter.providerId()).getBaseUrl();
		}
		// MOCK: the embedded mock server lives in this same app; resolve the live port
		// so it works for bootRun (8080) and random-port tests alike.
		int port = webServerContext.getObject().getWebServer().getPort();
		return "http://localhost:" + port + "/mock/" + adapter.providerId();
	}
}
