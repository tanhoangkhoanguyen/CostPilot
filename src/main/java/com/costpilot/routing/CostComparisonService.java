package com.costpilot.routing;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.Cost;
import com.costpilot.cost.CostEstimator;
import com.costpilot.cost.PriceLookupService;
import com.costpilot.cost.PriceNotFoundException;
import com.costpilot.provider.ProviderRegistry;

/**
 * 7.1: pre-call cost comparison. For a request and the set of policy-allowed
 * models, estimate what THIS request would cost on each model - same token
 * estimate (the request's, not the model's), each model's own versioned price
 * active at request time - and rank cheapest first. The 7.2 router walks this
 * list for the cheapest candidate that satisfies the request's quality bar.
 */
@Service
public class CostComparisonService {

	private static final Logger log = LoggerFactory.getLogger(CostComparisonService.class);

	private final CostEstimator estimator;
	private final PriceLookupService priceLookup;
	private final ProviderRegistry registry;

	public CostComparisonService(CostEstimator estimator, PriceLookupService priceLookup,
			ProviderRegistry registry) {
		this.estimator = estimator;
		this.priceLookup = priceLookup;
		this.registry = registry;
	}

	/**
	 * Cheapest-first candidates among the allowed models. A model without a price
	 * version active at {@code at} cannot be ranked and is skipped (logged) - it
	 * is not a routing candidate rather than an error. Ties break on model name
	 * so the ranking is deterministic.
	 */
	public List<ModelCostCandidate> rank(CanonicalChatRequest request, Collection<String> allowedModels, Instant at) {
		return allowedModels.stream()
				.distinct()
				.map(model -> candidate(request, model, at))
				.filter(java.util.Objects::nonNull)
				.sorted(Comparator
						.comparing((ModelCostCandidate c) -> c.estimate().total())
						.thenComparing(ModelCostCandidate::model))
				.toList();
	}

	private ModelCostCandidate candidate(CanonicalChatRequest request, String model, Instant at) {
		String provider = registry.forModel(model).providerId();
		try {
			var price = priceLookup.priceAt(provider, model, at);
			Cost estimate = estimator.estimateMax(request, price);
			return new ModelCostCandidate(provider, model, estimate, price.getId());
		} catch (PriceNotFoundException e) {
			log.debug("cost comparison skips unpriced model={} provider={}", model, provider);
			return null;
		}
	}
}
