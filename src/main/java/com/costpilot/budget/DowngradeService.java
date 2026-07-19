package com.costpilot.budget;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.CostEstimator;
import com.costpilot.cost.LedgerContext;
import com.costpilot.domain.ModelPrice;
import com.costpilot.domain.ModelPriceRepository;
import com.costpilot.policy.PolicyService;
import com.costpilot.routing.RoutingService;

/**
 * Price-aware model selection for 4.1: when the estimated cost of the requested
 * model does not fit the remaining budget, propose cheaper POLICY-ALLOWED models,
 * cheapest first. The caller retries the atomic budget reservation per candidate,
 * so the fit decision stays race-free in Redis.
 */
@Service
public class DowngradeService {

	private static final Logger log = LoggerFactory.getLogger(DowngradeService.class);

	public record Candidate(String model, BigDecimal estimatedCost) {
	}

	private final ModelPriceRepository prices;
	private final CostEstimator estimator;
	private final PolicyService policyService;
	private final RoutingService routingService;

	public DowngradeService(ModelPriceRepository prices, CostEstimator estimator, PolicyService policyService,
			RoutingService routingService) {
		this.prices = prices;
		this.estimator = estimator;
		this.policyService = policyService;
		this.routingService = routingService;
	}

	/** Cheaper policy-allowed alternatives to the request's model, cheapest first. */
	public List<Candidate> cheaperAllowedAlternatives(CanonicalChatRequest request, LedgerContext context) {
		return cheaperAllowedAlternatives(request, context, null);
	}

	/**
	 * Same, but honoring a declared quality bar (7.2): when minTier is set, a
	 * budget downgrade may only land on a model whose tier still meets the bar -
	 * "cheaper" never means "below the bar the client declared".
	 */
	public List<Candidate> cheaperAllowedAlternatives(CanonicalChatRequest request, LedgerContext context,
			Integer minTier) {
		BigDecimal originalEstimate = prices.findAllLive().stream()
				.filter(p -> p.getModel().equals(request.model()))
				.findFirst()
				.map(p -> estimator.estimateMax(request, p).total())
				.orElse(null);
		if (originalEstimate == null) {
			return List.of();
		}
		List<Candidate> candidates = prices.findAllLive().stream()
				.filter(p -> !p.getModel().equals(request.model()))
				.map(p -> new Candidate(p.getModel(), estimateFor(request, p)))
				.filter(c -> c.estimatedCost().compareTo(originalEstimate) < 0)
				.filter(c -> policyService.allows(context, c.model()))
				.filter(c -> minTier == null || routingService.tierOf(c.model())
						.map(tier -> tier >= minTier).orElse(false))
				.sorted(Comparator.comparing(Candidate::estimatedCost))
				.toList();
		log.debug("downgrade candidates for model={}: {}", request.model(),
				candidates.stream().map(Candidate::model).toList());
		return candidates;
	}

	private BigDecimal estimateFor(CanonicalChatRequest request, ModelPrice price) {
		CanonicalChatRequest onModel = new CanonicalChatRequest(
				price.getModel(), request.messages(), request.maxTokens(), request.stream());
		return estimator.estimateMax(onModel, price).total();
	}
}
