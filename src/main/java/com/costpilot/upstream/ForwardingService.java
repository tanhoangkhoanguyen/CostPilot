package com.costpilot.upstream;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.costpilot.budget.BudgetService;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.core.model.UsageEvent;
import com.costpilot.cost.AuditService;
import com.costpilot.cost.Cost;
import com.costpilot.cost.CostEstimator;
import com.costpilot.cost.CostService;
import com.costpilot.cost.DecisionContext;
import com.costpilot.cost.PriceNotFoundException;
import com.costpilot.cost.StreamCostMeter;
import com.costpilot.cost.UsageLedgerService;
import com.costpilot.domain.AuditRecord;
import com.costpilot.kafka.UsageEventPublisher;
import com.costpilot.metrics.GovernanceMetrics;
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
	private final AuditService auditService;
	// optional: present only when costpilot.kafka.enabled=true (5.2)
	private final ObjectProvider<UsageEventPublisher> eventPublisher;
	private final CostEstimator estimator;
	private final WebClient webClient;
	private final ObjectProvider<WebServerApplicationContext> webServerContext;
	private final GovernanceMetrics metrics;

	public ForwardingService(UpstreamProperties properties, ProviderRegistry registry,
			CostService costService, UsageLedgerService usageLedger, AuditService auditService,
			ObjectProvider<UsageEventPublisher> eventPublisher,
			CostEstimator estimator, WebClient.Builder webClientBuilder,
			ObjectProvider<WebServerApplicationContext> webServerContext, GovernanceMetrics metrics) {
		this.properties = properties;
		this.registry = registry;
		this.costService = costService;
		this.usageLedger = usageLedger;
		this.auditService = auditService;
		this.eventPublisher = eventPublisher;
		this.estimator = estimator;
		this.webClient = webClientBuilder.build();
		this.webServerContext = webServerContext;
		this.metrics = metrics;
	}

	public Mono<CanonicalChatResponse> forward(CanonicalChatRequest request, DecisionContext decision) {
		ProviderAdapter adapter = registry.forModel(request.model());
		Instant requestedAt = Instant.now();
		long start = System.nanoTime();
		return exchange(adapter, request)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.bodyToMono(String.class)
				.map(adapter::parseResponse)
				.doOnNext(response -> {
					metrics.recordUpstreamLatency(adapter.providerId(), System.nanoTime() - start);
					ledger(adapter, request, decision, response.usage(), requestedAt);
				});
	}

	private void ledger(ProviderAdapter adapter, CanonicalChatRequest request, DecisionContext decision,
			Usage usage, Instant requestedAt) {
		if (usage == null) {
			return;
		}
		try {
			CostService.Priced priced = costService.pricedCostFor(
					adapter.providerId(), request.model(), usage, requestedAt);
			// 7.3: counterfactual savings - what this same token usage WOULD have cost on
			// the model the client originally asked for, minus what it actually cost on the
			// cheaper routed/downgraded model. Only meaningful when the two differ.
			java.math.BigDecimal savings = counterfactualSavings(decision, usage, priced.cost(), requestedAt);
			Long savingsNanos = savings != null ? BudgetService.toNanos(savings) : null;
			UsageLedgerService.LedgerResult result = usageLedger.record(decision.ledger(), adapter.providerId(),
					request.model(), usage, priced.cost(), priced.price().getId(), savingsNanos);
			// audit + event only on a fresh insert - a replayed request is already
			// recorded, so this is exactly one audit row / one usage event per forwarded
			// request (5.1, 5.2), reusing the ledger's replay-safe gate
			if (result.freshInsert()) {
				AuditRecord audit = auditService.recordForwarded(decision, adapter.providerId(), usage,
						priced.cost().total(), result.record());
				publishEvent(decision, adapter.providerId(), usage, priced.cost().total(), audit);
				// count tokens once per billed request (fresh insert = not a replay)
				metrics.recordTokens(usage.inputTokens(), usage.outputTokens());
				// 7.3: accumulate routing savings once per billed request, same replay gate
				if (savingsNanos != null) {
					metrics.recordRoutingSavings(savingsNanos);
				}
			}
		} catch (PriceNotFoundException e) {
			// unpriced model: nothing to ledger yet; the request itself must not fail
			// over missing price data. still audit the decision (cost null) so every
			// forwarded request is explainable (5.1).
			log.warn("skipping ledger write: {}", e.getMessage());
			auditService.recordForwarded(decision, adapter.providerId(), usage, null, null);
		}
	}

	/**
	 * 7.3: the money saved by routing/downgrading this request. Re-prices the actual
	 * observed token usage against the model the client originally requested, using the
	 * price version active at request time (2.3), and returns requested-cost minus
	 * actual-cost, floored at zero.
	 *
	 * <p>Returns null when there is nothing to measure: the executed model equals the
	 * requested one (no routing happened), or the requested model has no price on record.
	 * The counterfactual is a documented approximation - it charges the requested model
	 * for the output tokens the executed model actually produced, not for a fresh run of
	 * the requested model.
	 */
	private java.math.BigDecimal counterfactualSavings(DecisionContext decision, Usage usage,
			Cost actualCost, Instant requestedAt) {
		String requestedModel = decision.requestedModel();
		if (requestedModel == null || requestedModel.equals(decision.executedModel())) {
			return null;
		}
		try {
			String requestedProvider = registry.forModel(requestedModel).providerId();
			Cost counterfactual = costService.costFor(requestedProvider, requestedModel, usage, requestedAt);
			java.math.BigDecimal savings = counterfactual.total().subtract(actualCost.total());
			java.math.BigDecimal floored = savings.signum() > 0 ? savings : java.math.BigDecimal.ZERO;
			log.info("routing savings requested={} executed={} counterfactual={} actual={} savings={}",
					requestedModel, decision.executedModel(), counterfactual.total().toPlainString(),
					actualCost.total().toPlainString(), floored.toPlainString());
			return floored;
		} catch (PriceNotFoundException e) {
			// requested model unpriced: savings unknown, not zero - skip rather than
			// understate. The request itself is unaffected.
			log.warn("routing savings unknown: {}", e.getMessage());
			return null;
		}
	}

	// Build the append-only usage event from the just-written audit row and publish it
	// best-effort (5.2). No-op when Kafka is disabled (publisher bean absent). Cost is
	// carried as exact integer nanodollars; the event timestamp is the audit row's
	// created_at so windowed OLAP reconciliation lines up with the ledger.
	private void publishEvent(DecisionContext decision, String provider, Usage usage, java.math.BigDecimal cost,
			AuditRecord audit) {
		UsageEventPublisher publisher = eventPublisher.getIfAvailable();
		if (publisher == null) {
			return;
		}
		com.costpilot.cost.LedgerContext ctx = decision.ledger();
		UsageEvent event = new UsageEvent(
				audit.getId(), ctx.tenantId(), ctx.teamId(), ctx.projectId(), ctx.userId(), ctx.environment(),
				provider, decision.requestedModel(), decision.executedModel(),
				audit.getDecision(), audit.getFinishReason(),
				usage.inputTokens(), usage.outputTokens(),
				BudgetService.toNanos(cost), audit.getCreatedAt());
		publisher.publish(event);
	}

	public Flux<CanonicalStreamChunk> forwardStream(CanonicalChatRequest request, DecisionContext decision) {
		return forwardStream(request, decision, null);
	}

	/**
	 * @param cutoffAllowanceNanos the request's spend allowance (its reservation
	 *            plus the scope's remaining at stream start), or null when no
	 *            budget governs the request. When the accrued cost crosses it,
	 *            the upstream is cancelled and a clean truncation is emitted (4.3).
	 */
	public Flux<CanonicalStreamChunk> forwardStream(CanonicalChatRequest request, DecisionContext decision,
			Long cutoffAllowanceNanos) {
		ProviderAdapter adapter = registry.forModel(request.model());
		Instant requestedAt = Instant.now();
		long start = System.nanoTime();
		// mid-stream metering (4.2): accrue cost token-by-token as chunks arrive,
		// reconciled against provider-reported usage; unpriced models stream unmetered
		StreamCostMeter meter = meterOrNull(adapter, request, requestedAt);
		java.util.concurrent.atomic.AtomicBoolean cutOff = new java.util.concurrent.atomic.AtomicBoolean();
		return exchange(adapter, request)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.retrieve()
				.bodyToFlux(String.class)
				.flatMap(data -> adapter.parseStreamEvent(data).map(Flux::just).orElseGet(Flux::empty))
				.doOnNext(chunk -> {
					if (meter != null) {
						meter.observe(chunk);
						if (log.isDebugEnabled()) {
							log.debug("meter running model={} tokens={} cost={}", request.model(),
									meter.usage().totalTokens(), meter.runningCost().total().toPlainString());
						}
					}
				})
				// 4.3 headline: the check runs per chunk, so generation stops within
				// one token of crossing the allowance (bounded overshoot = 1 chunk).
				// takeUntil emits the crossing chunk, then cancels the upstream.
				.takeUntil(chunk -> {
					if (meter == null || cutoffAllowanceNanos == null || chunk.done()) {
						return false;
					}
					boolean crossed = BudgetService.toNanos(meter.runningCost().total()) >= cutoffAllowanceNanos;
					if (crossed && cutOff.compareAndSet(false, true)) {
						// single writer: the audit row (5.1) reads this once in doFinally
						decision.finishReason().set("budget_cutoff");
						metrics.cutoff();
						log.info("mid-stream cutoff model={} accruedTokens={} accruedCost={} allowance={}",
								request.model(), meter.usage().totalTokens(),
								meter.runningCost().total().toPlainString(),
								BudgetService.fromNanos(cutoffAllowanceNanos).toPlainString());
					}
					return crossed;
				})
				// clean truncation, not a broken socket: finish_reason then [DONE]
				.concatWith(Flux.defer(() -> cutOff.get()
						? Flux.just(CanonicalStreamChunk.finish("budget_cutoff"), CanonicalStreamChunk.endOfStream())
						: Flux.empty()))
				// doFinally, not doOnComplete: the gateway disposes this subscription as
				// soon as it relays [DONE], which cancels the flux before onComplete can
				// fire. Whatever ends the stream - complete, cancel, error - if tokens
				// were consumed they must be billed.
				.doFinally(signal -> {
					metrics.recordUpstreamLatency(adapter.providerId(), System.nanoTime() - start);
					if (meter != null && meter.usage().totalTokens() > 0) {
						log.info("stream meter final model={} inputTokens={} outputTokens={} cost={}",
								request.model(), meter.usage().inputTokens(), meter.usage().outputTokens(),
								meter.runningCost().total().toPlainString());
						ledger(adapter, request, decision, meter.usage(), requestedAt);
					}
				})
				// disposal propagates here when the client disconnects mid-stream;
				// cancelling the flux tears down the upstream HTTP connection
				.doOnCancel(() -> log.info("upstream stream cancelled provider={} model={}",
						adapter.providerId(), request.model()));
	}

	private StreamCostMeter meterOrNull(ProviderAdapter adapter, CanonicalChatRequest request, Instant at) {
		try {
			return costService.meter(adapter.providerId(), request.model(), at,
					estimator.estimateInputTokens(request));
		} catch (PriceNotFoundException e) {
			log.warn("stream unmetered: {}", e.getMessage());
			return null;
		}
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
