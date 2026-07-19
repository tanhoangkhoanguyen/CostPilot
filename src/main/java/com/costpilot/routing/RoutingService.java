package com.costpilot.routing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.LedgerContext;
import com.costpilot.domain.ModelCapability;
import com.costpilot.domain.ModelCapabilityRepository;
import com.costpilot.policy.PolicyService;

/**
 * 7.2: cheapest-that-qualifies routing. A request that declares a minimum
 * quality tier (X-CostPilot-Min-Tier) is routed to the CHEAPEST policy-allowed
 * model whose capability tier meets that bar - model choice becomes a spend
 * decision. Models with no capability row have unknown quality and are never
 * candidates. If nothing qualifies, the requested model stands unchanged (the
 * router only ever swaps to a model that provably meets the client's own bar).
 */
@Service
public class RoutingService {

	private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

	// tiers change rarely (admin data), routing sits on the hot path - snapshot the
	// whole small table and refresh at most every 30s
	private static final long TIER_CACHE_TTL_MS = 30_000;

	public record RoutingResult(String model, int tier, String reason) {
	}

	private record TierSnapshot(Map<String, Integer> tiers, long cachedAt) {
	}

	private final ModelCapabilityRepository capabilities;
	private final CostComparisonService comparison;
	private final PolicyService policyService;
	private final AtomicReference<TierSnapshot> cache = new AtomicReference<>();

	public RoutingService(ModelCapabilityRepository capabilities, CostComparisonService comparison,
			PolicyService policyService) {
		this.capabilities = capabilities;
		this.comparison = comparison;
		this.policyService = policyService;
	}

	/**
	 * The cheapest policy-allowed model with tier >= minTier for this request, or
	 * empty when no model qualifies. The result carries a human-readable reason
	 * for the audit trail.
	 */
	public Optional<RoutingResult> route(CanonicalChatRequest request, LedgerContext context, int minTier,
			Instant at) {
		Map<String, Integer> tiers = tiers();
		List<String> qualified = tiers.entrySet().stream()
				.filter(e -> e.getValue() >= minTier)
				.map(Map.Entry::getKey)
				.filter(model -> policyService.allows(context, model))
				.toList();
		List<ModelCostCandidate> ranked = comparison.rank(request, qualified, at);
		if (ranked.isEmpty()) {
			log.info("routing found no candidate with tier>={} for team={} - request stands on model={}",
					minTier, context.teamId(), request.model());
			return Optional.empty();
		}
		ModelCostCandidate cheapest = ranked.get(0);
		int tier = tiers.get(cheapest.model());
		return Optional.of(new RoutingResult(cheapest.model(), tier,
				"cheapest model with tier>=" + minTier + " (tier=" + tier
						+ ", est=" + cheapest.estimate().total().toPlainString() + ")"));
	}

	/** Tier for one model; empty when the model has no capability row. */
	public Optional<Integer> tierOf(String model) {
		return Optional.ofNullable(tiers().get(model));
	}

	private Map<String, Integer> tiers() {
		long now = System.currentTimeMillis();
		TierSnapshot snapshot = cache.get();
		if (snapshot == null || now - snapshot.cachedAt() > TIER_CACHE_TTL_MS) {
			snapshot = new TierSnapshot(capabilities.findAll().stream()
					.collect(Collectors.toUnmodifiableMap(ModelCapability::getModel, ModelCapability::getTier)),
					now);
			cache.set(snapshot);
		}
		return snapshot.tiers();
	}
}
