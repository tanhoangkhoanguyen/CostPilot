package com.costpilot.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.costpilot.api.dto.ChatCompletionResponse;
import com.costpilot.budget.BudgetService;
import com.costpilot.cache.PromptCacheRepository.Hit;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.Usage;
import com.costpilot.cost.CostService;
import com.costpilot.cost.LedgerContext;
import com.costpilot.cost.PriceNotFoundException;
import com.costpilot.metrics.GovernanceMetrics;
import com.costpilot.provider.ProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 10: the semantic cache as a spend-reduction layer. On a request, embed the prompt
 * and look up the nearest cached prompt within the same tenant/team; if the cosine
 * similarity clears a conservative threshold, serve the cached response at $0 provider
 * cost and record the would-be cost as savings (precision over recall - a high threshold
 * keeps the false-hit rate low). On a miss, the response is stored for next time.
 */
@Service
public class SemanticCacheService {

	private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

	private final PromptCacheRepository repository;
	private final CacheHitLogRepository hitLog;
	private final Embedder embedder;
	private final GovernanceMetrics metrics;
	private final ObjectMapper objectMapper;
	private final CostService costService;
	private final ProviderRegistry registry;
	private final boolean enabled;
	private final double threshold;
	private final Duration ttl;

	public SemanticCacheService(PromptCacheRepository repository, CacheHitLogRepository hitLog,
			Embedder embedder, GovernanceMetrics metrics, ObjectMapper objectMapper, CostService costService,
			ProviderRegistry registry,
			// off by default; a cost-optimization the org opts into. Conservative 0.97
			// cosine threshold keeps the false-hit rate low (precision over recall).
			@Value("${costpilot.cache.enabled:false}") boolean enabled,
			@Value("${costpilot.cache.similarity-threshold:0.97}") double threshold,
			// 2.4 (#98): entry lifetime. A hit older than the TTL is never served (a
			// semantic cache with no expiry serves unboundedly stale answers as "free");
			// the eviction sweep deletes past-TTL rows on the same clock.
			@Value("${costpilot.cache.ttl:PT24H}") Duration ttl) {
		this.repository = repository;
		this.hitLog = hitLog;
		this.embedder = embedder;
		this.metrics = metrics;
		this.objectMapper = objectMapper;
		this.costService = costService;
		this.registry = registry;
		this.enabled = enabled;
		this.threshold = threshold;
		this.ttl = ttl;
	}

	/** 2.4 (#98): the TTL cutoff — entries created before this instant are stale. */
	public Instant staleBefore() {
		return Instant.now().minus(ttl);
	}

	public boolean enabled() {
		return enabled;
	}

	/**
	 * Look for a cached answer close enough to serve. On a hit: no provider call, the
	 * would-be provider cost is recorded as cache savings, and the cached response is
	 * returned. On a miss (or when disabled): empty, and the caller forwards normally.
	 */
	public Optional<ChatCompletionResponse> lookup(CanonicalChatRequest request, LedgerContext ledger) {
		if (!enabled) {
			return Optional.empty();
		}
		float[] embedding = embedder.embed(promptText(request));
		// 2.4 (#98): exclude past-TTL rows from the match, so a stale entry is never served
		// even in the window before the eviction sweep removes it.
		Optional<Hit> hit = repository.nearest(ledger.tenantId(), ledger.teamId(), embedding, staleBefore());
		if (hit.isEmpty() || hit.get().similarity() < threshold) {
			metrics.cacheMiss();
			log.debug("cache miss tenant={} team={} bestSimilarity={}",
					ledger.tenantId(), ledger.teamId(), hit.map(Hit::similarity).orElse(null));
			return Optional.empty();
		}
		Hit h = hit.get();
		metrics.cacheHit();
		// 10.3 / #93: the hit avoided a provider call - its would-be cost is money saved.
		// Persist to cache_hit_log (analytics money truth) AND the Prometheus counter
		// (Grafana). Never write usage_record.savings_nanos here - that column is
		// routing/downgrade only, so the two channels cannot double-count.
		metrics.recordCacheSavings(h.costNanos());
		try {
			hitLog.record(ledger.tenantId(), ledger.teamId(), h.costNanos());
		} catch (Exception e) {
			log.warn("cache hit log skipped: {}", e.getMessage());
		}
		log.info("cache hit tenant={} team={} similarity={} savedNanos={}",
				ledger.tenantId(), ledger.teamId(), h.similarity(), h.costNanos());
		return deserialize(h.response());
	}

	/**
	 * Store a freshly-forwarded response so a future similar prompt hits (10.1/10.2). The
	 * would-be provider cost recorded here is what a future hit will count as savings; it
	 * is priced from the response's own usage at the current version, so an unpriced
	 * model simply is not cached. Best-effort: a store failure never fails the request.
	 */
	public void store(CanonicalChatRequest request, LedgerContext ledger, ChatCompletionResponse response) {
		if (!enabled || response.usage() == null) {
			return;
		}
		try {
			int inputTokens = response.usage().promptTokens();
			int outputTokens = response.usage().completionTokens();
			long costNanos = priceNanos(response.model(), inputTokens, outputTokens);
			float[] embedding = embedder.embed(promptText(request));
			repository.store(ledger.tenantId(), ledger.teamId(), promptText(request), embedding,
					response.model(), objectMapper.writeValueAsString(response),
					inputTokens, outputTokens, costNanos);
		} catch (PriceNotFoundException unpriced) {
			log.debug("cache store skipped, model unpriced: {}", unpriced.getMessage());
		} catch (Exception e) {
			// caching is best-effort: a store failure must never fail the served request
			log.warn("cache store skipped: {}", e.getMessage());
		}
	}

	private long priceNanos(String model, int inputTokens, int outputTokens) {
		String provider = registry.forModel(model).providerId();
		var cost = costService.costFor(provider, model, new Usage(inputTokens, outputTokens), Instant.now());
		return BudgetService.toNanos(cost.total());
	}

	// the prompt we match on: the concatenated message contents
	private static String promptText(CanonicalChatRequest request) {
		StringBuilder sb = new StringBuilder();
		for (CanonicalChatRequest.Message m : request.messages()) {
			if (m.content() != null) {
				sb.append(m.content()).append('\n');
			}
		}
		return sb.toString();
	}

	private Optional<ChatCompletionResponse> deserialize(String json) {
		try {
			return Optional.of(objectMapper.readValue(json, ChatCompletionResponse.class));
		} catch (Exception e) {
			log.warn("cached response unreadable, treating as miss: {}", e.getMessage());
			return Optional.empty();
		}
	}
}
