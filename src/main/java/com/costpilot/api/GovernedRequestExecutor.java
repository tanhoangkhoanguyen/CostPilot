package com.costpilot.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.api.dto.ChatCompletionResponse;
import com.costpilot.api.dto.ChatMessage;
import com.costpilot.budget.BudgetExceededException;
import com.costpilot.budget.BudgetGuard;
import com.costpilot.budget.DowngradeService;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.cost.AuditService;
import com.costpilot.cost.DecisionContext;
import com.costpilot.cost.LedgerContext;
import com.costpilot.metrics.GovernanceMetrics;
import com.costpilot.routing.RoutingService;
import com.costpilot.upstream.ForwardingService;

/**
 * The governed-execution pipeline shared by the live request path and the
 * approval-resume path (Stage 8): cost routing (7.2) -> budget reserve with pre-flight
 * auto-downgrade (4.1) -> forward + meter + ledger. Extracting it means an approved
 * parked request runs the exact same governance as a live one, once.
 *
 * <p>Streaming stays in the controller - it is bound to the live client connection and
 * an approved request always resumes non-streaming (D3). This owns the non-streaming
 * path and the route/budget preparation that streaming also reuses.
 */
@Service
public class GovernedRequestExecutor {

	private static final Logger log = LoggerFactory.getLogger(GovernedRequestExecutor.class);

	/** Header side-effects; the live path sinks to the servlet response, resume to a no-op. */
	@FunctionalInterface
	public interface HeaderSink {
		void set(String name, String value);

		HeaderSink NONE = (name, value) -> {
		};
	}

	/** The route/budget outcome: the model actually reserved plus its guard reservation. */
	public record Prepared(CanonicalChatRequest request, DecisionContext decision, BudgetGuard.GuardResult guard) {
	}

	private final ForwardingService forwardingService;
	private final BudgetGuard budgetGuard;
	private final DowngradeService downgradeService;
	private final AuditService auditService;
	private final GovernanceMetrics metrics;
	private final RoutingService routingService;

	public GovernedRequestExecutor(ForwardingService forwardingService, BudgetGuard budgetGuard,
			DowngradeService downgradeService, AuditService auditService, GovernanceMetrics metrics,
			RoutingService routingService) {
		this.forwardingService = forwardingService;
		this.budgetGuard = budgetGuard;
		this.downgradeService = downgradeService;
		this.auditService = auditService;
		this.metrics = metrics;
		this.routingService = routingService;
	}

	/**
	 * Cost-route (7.2) then reserve budget with pre-flight auto-downgrade (4.1). The
	 * returned guard must be released by the caller once the request settles. A 402
	 * escapes (as {@link BudgetExceededException}) only when nothing fits; the rejection
	 * is audited here so the 402 stays explainable.
	 */
	public Prepared prepare(CanonicalChatRequest canonical, DecisionContext decision, String requestedModel,
			Integer minTier, HeaderSink headers) {
		// 7.2: route to the cheapest policy-allowed model meeting the declared bar. Only
		// when the incoming decision is a plain ALLOW - an explicit downgrade wins over
		// cost routing.
		if (minTier != null && decision.decision() == com.costpilot.policy.PolicyDecision.Decision.ALLOW) {
			var routed = routingService.route(canonical, decision.ledger(), minTier, Instant.now());
			if (routed.isPresent() && !routed.get().model().equals(canonical.model())) {
				var route = routed.get();
				metrics.routed();
				log.info("cost-routed minTier={} original={} executed={} reason=\"{}\"",
						minTier, canonical.model(), route.model(), route.reason());
				headers.set("X-CostPilot-Model-Routed",
						canonical.model() + " -> " + route.model() + "; " + route.reason());
				canonical = new CanonicalChatRequest(route.model(), canonical.messages(),
						canonical.maxTokens(), canonical.stream());
				decision = DecisionContext.routed(decision.ledger(), requestedModel, route.model(), route.reason());
			}
		}

		// hot-path budget check: a hard block triggers pre-flight auto-downgrade to a
		// cheaper policy-allowed model (4.1); only when nothing fits does the 402 escape.
		BudgetGuard.GuardResult guard;
		long guardStart = System.nanoTime();
		try {
			guard = budgetGuard.reserve(canonical, decision.ledger());
		} catch (BudgetExceededException blocked) {
			DowngradeOutcome outcome;
			try {
				outcome = downgradeForBudget(canonical, decision.ledger(), blocked, minTier);
			} catch (BudgetExceededException nothingFits) {
				auditService.recordRejected(DecisionContext.budgetBlocked(decision.ledger(), requestedModel,
						nothingFits.getScope().dbValue()));
				metrics.budgetRejection();
				throw nothingFits;
			}
			metrics.downgrade("budget");
			log.info("auto-downgrade reason=budget original={} executed={} blockedScope={}",
					canonical.model(), outcome.request().model(), blocked.getScope().dbValue());
			headers.set("X-CostPilot-Model-Downgraded",
					canonical.model() + " -> " + outcome.request().model() + "; reason=budget");
			canonical = outcome.request();
			guard = outcome.guard();
			// original stays the client's requested model even if policy already downgraded once
			decision = DecisionContext.downgrade(decision.ledger(), requestedModel, outcome.request().model(),
					"budget", null, blocked.getScope().dbValue());
		}
		metrics.recordGuardLatency(System.nanoTime() - guardStart);
		if (guard.warning() != null) {
			metrics.softLimitWarning();
			headers.set("X-CostPilot-Budget-Warning", guard.warning());
		}
		return new Prepared(canonical, decision, guard);
	}

	/**
	 * Full non-streaming governed execution: prepare (route + budget) then forward +
	 * meter + ledger, releasing the reservation once the request settles. Used by the
	 * live non-streaming path and by approval-resume (D3).
	 */
	public ChatCompletionResponse executeNonStreaming(CanonicalChatRequest canonical, DecisionContext decision,
			String requestedModel, Integer minTier, HeaderSink headers) {
		Prepared prepared = prepare(canonical, decision, requestedModel, minTier, headers);
		try {
			CanonicalChatResponse upstream = forwardingService.forward(prepared.request(), prepared.decision())
					.block();
			return render(prepared.request(), upstream);
		} finally {
			budgetGuard.release(prepared.guard());
		}
	}

	private record DowngradeOutcome(CanonicalChatRequest request, BudgetGuard.GuardResult guard) {
	}

	/**
	 * 4.1: the requested model does not fit the remaining budget - retry the atomic
	 * reservation on cheaper policy-allowed models, cheapest first. A declared tier bar
	 * (7.2) bounds this: budget pressure never downgrades below the bar.
	 */
	private DowngradeOutcome downgradeForBudget(CanonicalChatRequest canonical, LedgerContext ledgerContext,
			BudgetExceededException blocked, Integer minTier) {
		for (DowngradeService.Candidate candidate : downgradeService
				.cheaperAllowedAlternatives(canonical, ledgerContext, minTier).stream().limit(3).toList()) {
			CanonicalChatRequest alternative = new CanonicalChatRequest(candidate.model(),
					canonical.messages(), canonical.maxTokens(), canonical.stream());
			try {
				return new DowngradeOutcome(alternative, budgetGuard.reserve(alternative, ledgerContext));
			} catch (BudgetExceededException stillBlocked) {
				// try the next candidate
			}
		}
		throw blocked;
	}

	ChatCompletionResponse render(CanonicalChatRequest request, CanonicalChatResponse upstream) {
		String id = upstream.id() != null ? upstream.id() : "chatcmpl-" + UUID.randomUUID();
		String model = upstream.model() != null ? upstream.model() : request.model();
		ChatCompletionResponse.Usage usage = upstream.usage() == null
				? new ChatCompletionResponse.Usage(0, 0, 0)
				: new ChatCompletionResponse.Usage(upstream.usage().inputTokens(), upstream.usage().outputTokens(),
						upstream.usage().totalTokens());
		return new ChatCompletionResponse(
				id, "chat.completion", Instant.now().getEpochSecond(), model,
				List.of(new ChatCompletionResponse.Choice(0,
						new ChatMessage("assistant", upstream.content()),
						upstream.finishReason() == null ? "stop" : upstream.finishReason())),
				usage);
	}
}
