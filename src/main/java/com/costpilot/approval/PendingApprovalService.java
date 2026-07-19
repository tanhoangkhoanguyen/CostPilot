package com.costpilot.approval;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.costpilot.budget.BudgetService;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.CostEstimator;
import com.costpilot.cost.LedgerContext;
import com.costpilot.cost.PriceLookupService;
import com.costpilot.cost.PriceNotFoundException;
import com.costpilot.domain.ModelPrice;
import com.costpilot.domain.PendingApproval;
import com.costpilot.domain.PendingApprovalRepository;
import com.costpilot.provider.ProviderRegistry;

/**
 * Stage 8.1: parks REQUIRE_APPROVAL requests and computes the pre-flight estimate that
 * both the cost-threshold trigger and the approver's view rely on. The store is Postgres,
 * so a parked request survives a restart. State transitions (8.2) live in
 * {@link ApprovalDecisionService}.
 */
@Service
public class PendingApprovalService {

	private static final Logger log = LoggerFactory.getLogger(PendingApprovalService.class);

	private final PendingApprovalRepository repository;
	private final RequestPayloadCodec codec;
	private final CostEstimator estimator;
	private final PriceLookupService priceLookup;
	private final ProviderRegistry registry;
	private final Duration ttl;

	public PendingApprovalService(PendingApprovalRepository repository, RequestPayloadCodec codec,
			CostEstimator estimator, PriceLookupService priceLookup, ProviderRegistry registry,
			// documented default TTL: 24h. A pending request past this is auto-rejected (8.2).
			@Value("${costpilot.approval.ttl:PT24H}") Duration ttl) {
		this.repository = repository;
		this.codec = codec;
		this.estimator = estimator;
		this.priceLookup = priceLookup;
		this.registry = registry;
		this.ttl = ttl;
	}

	/**
	 * Pre-flight MAX cost of this request on its requested model, in exact nanodollars,
	 * or null when the model is unpriced (no cost gate can apply). Reuses the same
	 * conservative estimator the budget guard reserves against (4.1).
	 */
	public Long estimateMaxNanos(CanonicalChatRequest request) {
		try {
			String provider = registry.forModel(request.model()).providerId();
			ModelPrice price = priceLookup.priceAt(provider, request.model(), Instant.now());
			return BudgetService.toNanos(estimator.estimateMax(request, price).total());
		} catch (PriceNotFoundException e) {
			log.warn("approval estimate skipped, model unpriced: {}", e.getMessage());
			return null;
		}
	}

	/** Persist a parked request with full replay context; returns the pending handle. */
	@Transactional
	public PendingApproval park(CanonicalChatRequest request, LedgerContext ledger, String requestedModel,
			Integer minTier, String reason, java.util.UUID matchedRuleId) {
		Long estimate = estimateMaxNanos(request);
		PendingApproval pending = new PendingApproval(
				ledger.tenantId(), ledger.teamId(), ledger.projectId(), ledger.userId(), ledger.environment(),
				ledger.idempotencyKey(), requestedModel, minTier, codec.serialize(request),
				estimate, reason, matchedRuleId, Instant.now().plus(ttl));
		return repository.save(pending);
	}
}
