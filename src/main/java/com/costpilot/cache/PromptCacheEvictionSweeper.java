package com.costpilot.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.costpilot.metrics.GovernanceMetrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 2.4 (#98): bounds the semantic cache in time and size. Every other cache in the system
 * (policy, budget, routing, api-key) has a TTL; prompt_cache had none, so it grew unbounded
 * and could serve unboundedly stale answers as "free". This scheduled sweep deletes
 * past-TTL rows (created_at &lt; now − ttl); the lookup already excludes them from matches
 * (see {@link SemanticCacheService#staleBefore()}), so eviction here reclaims storage.
 *
 * <p>Mirrors {@code ApprovalExpirySweeper}: a {@code @Scheduled(fixedDelayString=...)} bean
 * whose interval is a config property. Only active when the cache is enabled - when the
 * cache is off there is nothing to sweep, so the bean is not created at all.
 */
@Component
@ConditionalOnProperty(name = "costpilot.cache.enabled", havingValue = "true")
public class PromptCacheEvictionSweeper {

	private static final Logger log = LoggerFactory.getLogger(PromptCacheEvictionSweeper.class);

	private final PromptCacheRepository repository;
	private final SemanticCacheService cache;
	private final GovernanceMetrics metrics;

	public PromptCacheEvictionSweeper(PromptCacheRepository repository, SemanticCacheService cache,
			GovernanceMetrics metrics, MeterRegistry registry) {
		this.repository = repository;
		this.cache = cache;
		this.metrics = metrics;
		// live cache size (USE saturation signal) - a gauge so the dashboard can show the
		// cache staying bounded under a flood, and that eviction actually reclaims rows.
		Gauge.builder("costpilot.cache.size", repository, PromptCacheRepository::count)
				.description("current number of semantic-cache entries")
				.register(registry);
	}

	@Scheduled(fixedDelayString = "${costpilot.cache.eviction-interval-ms:60000}")
	public void sweep() {
		int evicted = repository.deleteExpired(cache.staleBefore());
		if (evicted > 0) {
			metrics.cacheEviction(evicted);
			log.info("semantic cache eviction removed {} past-TTL entr{}", evicted, evicted == 1 ? "y" : "ies");
		}
	}
}
