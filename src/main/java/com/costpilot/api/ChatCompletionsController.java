package com.costpilot.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.costpilot.api.dto.ChatCompletionChunk;
import com.costpilot.api.dto.ChatCompletionRequest;
import com.costpilot.budget.BudgetGuard;
import com.costpilot.budget.BudgetService;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.cost.AuditService;
import com.costpilot.cost.DecisionContext;
import com.costpilot.cost.LedgerContext;
import com.costpilot.policy.PolicyDecision;
import com.costpilot.policy.PolicyDeniedException;
import com.costpilot.metrics.GovernanceMetrics;
import com.costpilot.policy.PolicyService;
import com.costpilot.security.AuthenticatedPrincipal;
import com.costpilot.security.CurrentPrincipal;
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
	private final BudgetService budgetService;
	private final AuditService auditService;
	private final GovernanceMetrics metrics;
	private final GovernedRequestExecutor executor;
	private final com.costpilot.approval.PendingApprovalService approvalService;

	public ChatCompletionsController(ForwardingService forwardingService, BudgetGuard budgetGuard,
			PolicyService policyService, BudgetService budgetService,
			AuditService auditService, GovernanceMetrics metrics,
			GovernedRequestExecutor executor, com.costpilot.approval.PendingApprovalService approvalService) {
		this.forwardingService = forwardingService;
		this.budgetGuard = budgetGuard;
		this.policyService = policyService;
		this.budgetService = budgetService;
		this.auditService = auditService;
		this.metrics = metrics;
		this.executor = executor;
		this.approvalService = approvalService;
	}

	@PostMapping(value = "/v1/chat/completions", produces = { MediaType.APPLICATION_JSON_VALUE,
			MediaType.TEXT_EVENT_STREAM_VALUE })
	public Object chatCompletions(
			@Valid @RequestBody ChatCompletionRequest request,
			@RequestHeader(value = "X-Team-ID", required = false) String teamHeader,
			@RequestHeader(value = "X-Project-ID", required = false) String projectHeader,
			@RequestHeader(value = "X-User-ID", required = false) String userId,
			@RequestHeader(value = "X-Environment", required = false) String environment,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestHeader(value = "X-CostPilot-Min-Tier", required = false) Integer minTier,
			HttpServletResponse servletResponse) {

		// 6.1: identity now comes from the authenticated API key, not trusted headers.
		// A tenant-admin key may still impersonate any team via X-Team-ID/X-Project-ID
		// (ops/testing); a normal team key is force-pinned to its own team and the headers
		// are ignored. This is what enforces per-team isolation on the data plane.
		AuthenticatedPrincipal principal = CurrentPrincipal.require();
		String tenantId = principal.tenantId();
		String teamId = principal.admin() && teamHeader != null && !teamHeader.isBlank()
				? teamHeader : principal.teamId();
		String projectId = principal.admin()
				? (projectHeader != null && !projectHeader.isBlank() ? projectHeader : principal.projectId())
				: principal.projectId();

		RequestContext context = RequestContext.of(teamId, projectId);
		log.info("chat.completions tenant={} team={} project={} model={} stream={} admin={}",
				tenantId, context.teamId(), context.projectId(), request.model(), request.isStreaming(),
				principal.admin());

		// client-supplied Idempotency-Key makes retries replay-safe in the ledger;
		// without one, each request is its own ledger entry
		LedgerContext ledgerContext = new LedgerContext(tenantId, teamId, projectId, userId, environment,
				idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : UUID.randomUUID().toString());

		CanonicalChatRequest canonical = CanonicalChatRequest.from(request);
		// the model the client asked for, held across any downgrade so the audit trail
		// (5.1) can record original-vs-executed
		String requestedModel = canonical.model();
		// the "why" accumulated across policy + budget; folded into DecisionContext below
		DecisionContext decision = DecisionContext.allow(ledgerContext, requestedModel);

		// policy first (3.3): who may use what. DENY throws 403; REQUIRE_APPROVAL parks
		// (Stage 8); DOWNGRADE swaps the executed model before budget + forwarding.
		PolicyDecision policy = policyService.evaluate(ledgerContext, canonical.model());
		switch (policy.decision()) {
			case DENY -> {
				// audit before throwing: a denied request never forwards, but 5.1 still
				// requires a queryable row explaining why it was blocked
				auditService.recordRejected(rejectedContext(ledgerContext, requestedModel, policy));
				metrics.policyRejection("deny");
				throw new PolicyDeniedException(policy, canonical.model());
			}
			case REQUIRE_APPROVAL -> {
				return park(canonical, ledgerContext, requestedModel, minTier, policy, policy.reason());
			}
			case DOWNGRADE -> {
				metrics.downgrade("policy");
				servletResponse.setHeader("X-CostPilot-Model-Downgraded",
						canonical.model() + " -> " + policy.executedModel() + "; reason=policy");
				canonical = new CanonicalChatRequest(policy.executedModel(), canonical.messages(),
						canonical.maxTokens(), canonical.stream());
				decision = DecisionContext.downgrade(ledgerContext, requestedModel, policy.executedModel(),
						"policy", policy.matchedRuleId(), null);
			}
			case ALLOW -> {
				// 8.1 cost-threshold gate: an otherwise-allowed request whose pre-flight
				// MAX estimate exceeds the rule's threshold still needs human approval.
				if (policy.approvalThresholdNanos() != null) {
					Long estimate = approvalService.estimateMaxNanos(canonical);
					if (estimate != null && estimate > policy.approvalThresholdNanos()) {
						String reason = "estimated cost " + estimate + " over approval threshold "
								+ policy.approvalThresholdNanos();
						return park(canonical, ledgerContext, requestedModel, minTier, policy, reason);
					}
				}
			}
			case ROUTE -> {
				// policy evaluation never emits ROUTE - it comes from the router
			}
		}

		// route (7.2) + budget reserve/downgrade (4.1) live in the shared executor so the
		// approval-resume path runs identical governance.
		if (canonical.stream()) {
			GovernedRequestExecutor.Prepared prepared = executor.prepare(canonical, decision, requestedModel,
					minTier, servletResponse::setHeader);
			return relayStream(prepared.request(), prepared.decision(), prepared.guard(),
					cutoffAllowance(prepared.guard()));
		}
		return executor.executeNonStreaming(canonical, decision, requestedModel, minTier,
				servletResponse::setHeader);
	}

	// 8.1: park a REQUIRE_APPROVAL request (model-set or cost-threshold triggered) instead
	// of rejecting it. Audit the decision, persist the full request context, and hand the
	// caller a 202 with a pending id it can poll. The request is NOT forwarded.
	private ResponseEntity<Map<String, Object>> park(CanonicalChatRequest canonical, LedgerContext ledgerContext,
			String requestedModel, Integer minTier, PolicyDecision policy, String reason) {
		auditService.recordRejected(DecisionContext.rejected(ledgerContext, requestedModel,
				PolicyDecision.Decision.REQUIRE_APPROVAL, reason, policy.matchedRuleId()));
		metrics.policyRejection("require_approval");
		var pending = approvalService.park(canonical, ledgerContext, requestedModel, minTier, reason,
				policy.matchedRuleId());
		log.info("parked for approval id={} model={} reason=\"{}\"", pending.getId(), requestedModel, reason);
		return ResponseEntity.accepted().body(Map.of(
				"id", pending.getId().toString(),
				"object", "approval.pending",
				"state", pending.getState().name(),
				"model", requestedModel,
				"reason", reason,
				"expires_at", pending.getExpiresAt().toString()));
	}

	// DENY never forwards - build the decision context for the audit row from the verdict.
	private static DecisionContext rejectedContext(LedgerContext ledgerContext, String requestedModel,
			PolicyDecision policy) {
		return DecisionContext.rejected(ledgerContext, requestedModel, policy.decision(),
				policy.reason(), policy.matchedRuleId());
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

	private SseEmitter relayStream(CanonicalChatRequest request, DecisionContext decision,
			BudgetGuard.GuardResult guard, Long cutoffAllowanceNanos) {
		String id = "chatcmpl-" + UUID.randomUUID();
		long created = Instant.now().getEpochSecond();
		SseEmitter emitter = new SseEmitter(0L);
		// some providers (gemini) have no [DONE] sentinel - the gateway's public
		// contract always ends with one, whether the adapter signals done or the
		// upstream flux just completes
		java.util.concurrent.atomic.AtomicBoolean doneSent = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		Disposable subscription = forwardingService.forwardStream(request, decision, cutoffAllowanceNanos)
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
