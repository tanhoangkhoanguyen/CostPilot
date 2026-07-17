package com.costpilot.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.costpilot.api.dto.ChatCompletionChunk;
import com.costpilot.api.dto.ChatCompletionRequest;
import com.costpilot.api.dto.ChatCompletionResponse;
import com.costpilot.api.dto.ChatMessage;
import com.costpilot.budget.BudgetExceededException;
import com.costpilot.budget.BudgetGuard;
import com.costpilot.budget.BudgetService;
import com.costpilot.budget.DowngradeService;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.cost.LedgerContext;
import com.costpilot.policy.ApprovalRequiredException;
import com.costpilot.policy.PolicyDecision;
import com.costpilot.policy.PolicyDeniedException;
import com.costpilot.policy.PolicyService;
import com.costpilot.upstream.ForwardingService;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import reactor.core.Disposable;

// Public OpenAI-compatible surface. Requests are normalized to the canonical model,
// forwarded through the provider adapter, and rendered back to the OpenAI schema -
// so the client always speaks OpenAI regardless of the upstream provider.
@RestController
public class ChatCompletionsController {

	private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

	private final ForwardingService forwardingService;
	private final BudgetGuard budgetGuard;
	private final PolicyService policyService;
	private final DowngradeService downgradeService;
	private final BudgetService budgetService;

	public ChatCompletionsController(ForwardingService forwardingService, BudgetGuard budgetGuard,
			PolicyService policyService, DowngradeService downgradeService, BudgetService budgetService) {
		this.forwardingService = forwardingService;
		this.budgetGuard = budgetGuard;
		this.policyService = policyService;
		this.downgradeService = downgradeService;
		this.budgetService = budgetService;
	}

	@PostMapping(value = "/v1/chat/completions", produces = { MediaType.APPLICATION_JSON_VALUE,
			MediaType.TEXT_EVENT_STREAM_VALUE })
	public Object chatCompletions(
			@Valid @RequestBody ChatCompletionRequest request,
			@RequestHeader(value = "X-Team-ID", required = false) String teamId,
			@RequestHeader(value = "X-Project-ID", required = false) String projectId,
			@RequestHeader(value = "X-User-ID", required = false) String userId,
			@RequestHeader(value = "X-Environment", required = false) String environment,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletResponse servletResponse) {

		RequestContext context = RequestContext.of(teamId, projectId);
		log.info("chat.completions team={} project={} model={} stream={}",
				context.teamId(), context.projectId(), request.model(), request.isStreaming());

		// client-supplied Idempotency-Key makes retries replay-safe in the ledger;
		// without one, each request is its own ledger entry
		LedgerContext ledgerContext = new LedgerContext(null, teamId, projectId, userId, environment,
				idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : UUID.randomUUID().toString());

		CanonicalChatRequest canonical = CanonicalChatRequest.from(request);

		// policy first (3.3): who may use what. DENY/REQUIRE_APPROVAL throw 403;
		// DOWNGRADE swaps the executed model before budget + forwarding
		PolicyDecision policy = policyService.evaluate(ledgerContext, canonical.model());
		switch (policy.decision()) {
			case DENY -> throw new PolicyDeniedException(policy, canonical.model());
			case REQUIRE_APPROVAL -> throw new ApprovalRequiredException(policy, canonical.model());
			case DOWNGRADE -> {
				servletResponse.setHeader("X-CostPilot-Model-Downgraded",
						canonical.model() + " -> " + policy.executedModel() + "; reason=policy");
				canonical = new CanonicalChatRequest(policy.executedModel(), canonical.messages(),
						canonical.maxTokens(), canonical.stream());
			}
			case ALLOW -> {
			}
		}

		// hot-path budget check: hard-block triggers pre-flight auto-downgrade to a
		// cheaper policy-allowed model (4.1); only when nothing fits does the 402
		// escape. soft limit serves the request with a warning header (3.2)
		BudgetGuard.GuardResult guard;
		try {
			guard = budgetGuard.reserve(canonical, ledgerContext);
		} catch (BudgetExceededException blocked) {
			DowngradeOutcome outcome = downgradeForBudget(canonical, ledgerContext, blocked);
			// audit trail (persisted in 5.1): original vs executed + why
			log.info("auto-downgrade reason=budget original={} executed={} blockedScope={}",
					canonical.model(), outcome.request().model(), blocked.getScope().dbValue());
			servletResponse.setHeader("X-CostPilot-Model-Downgraded",
					canonical.model() + " -> " + outcome.request().model() + "; reason=budget");
			canonical = outcome.request();
			guard = outcome.guard();
		}
		if (guard.warning() != null) {
			servletResponse.setHeader("X-CostPilot-Budget-Warning", guard.warning());
		}

		if (canonical.stream()) {
			return relayStream(canonical, ledgerContext, guard, cutoffAllowance(guard));
		}
		try {
			CanonicalChatResponse upstream = forwardingService.forward(canonical, ledgerContext).block();
			return render(canonical, upstream);
		} finally {
			budgetGuard.release(guard);
		}
	}

	private record DowngradeOutcome(CanonicalChatRequest request, BudgetGuard.GuardResult guard) {
	}

	/**
	 * 4.1: the requested model does not fit the remaining budget - retry the
	 * atomic reservation on cheaper policy-allowed models, cheapest first. If
	 * nothing fits, the original 402 propagates.
	 */
	private DowngradeOutcome downgradeForBudget(CanonicalChatRequest canonical, LedgerContext ledgerContext,
			BudgetExceededException blocked) {
		for (DowngradeService.Candidate candidate : downgradeService
				.cheaperAllowedAlternatives(canonical, ledgerContext).stream().limit(3).toList()) {
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

	private ChatCompletionResponse render(CanonicalChatRequest request, CanonicalChatResponse upstream) {
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

	/**
	 * The request's mid-stream spend allowance (4.3): its own reservation plus
	 * whatever remained in the tightest governed scope at stream start. Null when
	 * nothing governs the request - then there is nothing to cut off against.
	 */
	private Long cutoffAllowance(BudgetGuard.GuardResult guard) {
		Long allowance = null;
		for (BudgetGuard.Reservation reservation : guard.reservations()) {
			Long remaining = budgetService.remainingNanos(reservation.scope(), reservation.ref());
			if (remaining != null) {
				long candidate = remaining + reservation.nanos();
				allowance = allowance == null ? candidate : Math.min(allowance, candidate);
			}
		}
		return allowance;
	}

	private SseEmitter relayStream(CanonicalChatRequest request, LedgerContext ledgerContext,
			BudgetGuard.GuardResult guard, Long cutoffAllowanceNanos) {
		String id = "chatcmpl-" + UUID.randomUUID();
		long created = Instant.now().getEpochSecond();
		SseEmitter emitter = new SseEmitter(0L);
		// some providers (gemini) have no [DONE] sentinel - the gateway's public
		// contract always ends with one, whether the adapter signals done or the
		// upstream flux just completes
		java.util.concurrent.atomic.AtomicBoolean doneSent = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		Disposable subscription = forwardingService.forwardStream(request, ledgerContext, cutoffAllowanceNanos)
				.subscribe(
				chunk -> sendChunk(emitter, id, created, request.model(), chunk, doneSent),
				emitter::completeWithError,
				() -> finish(emitter, doneSent));
		Runnable settle = () -> {
			subscription.dispose();
			if (released.compareAndSet(false, true)) {
				budgetGuard.release(guard);
			}
		};
		// client went away -> stop pulling from the upstream, give the reservation back
		emitter.onCompletion(settle);
		emitter.onTimeout(settle);
		emitter.onError(t -> settle.run());
		return emitter;
	}

	private void finish(SseEmitter emitter, java.util.concurrent.atomic.AtomicBoolean doneSent) {
		try {
			if (doneSent.compareAndSet(false, true)) {
				emitter.send(SseEmitter.event().data("[DONE]"));
			}
			emitter.complete();
		} catch (Exception e) {
			emitter.completeWithError(e);
		}
	}

	private void sendChunk(SseEmitter emitter, String id, long created, String model, CanonicalStreamChunk chunk,
			java.util.concurrent.atomic.AtomicBoolean doneSent) {
		try {
			if (chunk.done()) {
				finish(emitter, doneSent);
				return;
			}
			if (chunk.usage() != null && chunk.contentDelta() == null && chunk.finishReason() == null) {
				emitter.send(SseEmitter.event().data(Map.of(
						"id", id, "object", "chat.completion.chunk", "created", created, "model", model,
						"choices", List.of(),
						"usage", Map.of(
								"prompt_tokens", chunk.usage().inputTokens(),
								"completion_tokens", chunk.usage().outputTokens(),
								"total_tokens", chunk.usage().totalTokens())),
						MediaType.APPLICATION_JSON));
				return;
			}
			ChatCompletionChunk.Delta delta = new ChatCompletionChunk.Delta(chunk.roleDelta(), chunk.contentDelta());
			emitter.send(SseEmitter.event().data(
					new ChatCompletionChunk(id, "chat.completion.chunk", created, model,
							List.of(new ChatCompletionChunk.ChunkChoice(0, delta, chunk.finishReason()))),
					MediaType.APPLICATION_JSON));
		} catch (Exception e) {
			emitter.completeWithError(e);
		}
	}
}
