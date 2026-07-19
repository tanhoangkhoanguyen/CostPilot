package com.costpilot.approval;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.costpilot.api.GovernedRequestExecutor;
import com.costpilot.api.dto.ChatCompletionResponse;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.DecisionContext;
import com.costpilot.cost.LedgerContext;
import com.costpilot.domain.PendingApproval;
import com.costpilot.domain.PendingApprovalRepository;

/**
 * Stage 8.2: the state machine that resumes or kills a parked request. Only the
 * pending -> approved | rejected | expired transition is allowed, exactly once
 * (guarded so a double-approve or approve-after-expire is a no-op). An approved
 * request replays through the shared governed executor - same routing, budget, meter,
 * and ledger as a live request - and its rendered response is stored on the handle.
 * Rejected and expired requests never reach a provider.
 */
@Service
public class ApprovalDecisionService {

	private static final Logger log = LoggerFactory.getLogger(ApprovalDecisionService.class);

	public record Outcome(PendingApproval pending, ChatCompletionResponse response) {
	}

	/** Thrown when a transition is attempted on an already-decided (non-pending) row. */
	public static class NotPendingException extends RuntimeException {
		public NotPendingException(UUID id, PendingApproval.State state) {
			super("approval " + id + " is already " + state);
		}
	}

	private final PendingApprovalRepository repository;
	private final RequestPayloadCodec codec;
	private final GovernedRequestExecutor executor;
	private final ResponseCodec responseCodec;

	public ApprovalDecisionService(PendingApprovalRepository repository, RequestPayloadCodec codec,
			GovernedRequestExecutor executor, ResponseCodec responseCodec) {
		this.repository = repository;
		this.codec = codec;
		this.executor = executor;
		this.responseCodec = responseCodec;
	}

	/**
	 * Approve: replay the parked request non-streaming through the governed executor
	 * (route + budget + forward + meter + ledger), store the rendered response, mark
	 * approved. The idempotency key is preserved so a replay of the approval is
	 * ledger-safe. A budget block at approval time still applies - the request may be
	 * auto-downgraded or 402 exactly as a live request would.
	 */
	@Transactional
	public Outcome approve(PendingApproval pending, String decidedBy) {
		requirePending(pending);
		CanonicalChatRequest request = codec.deserialize(pending.getRequestPayload());
		LedgerContext ledger = ledgerOf(pending);
		// approval granted: replay as a normal ALLOW on the requested model
		DecisionContext decision = DecisionContext.allow(ledger, request.model());
		ChatCompletionResponse response = executor.executeNonStreaming(request, decision, request.model(),
				pending.getMinTier(), GovernedRequestExecutor.HeaderSink.NONE);
		pending.setStoredResponse(responseCodec.serialize(response));
		pending.decide(PendingApproval.State.approved, decidedBy, "approved", Instant.now());
		repository.save(pending);
		log.info("approval approved id={} model={} decidedBy={}", pending.getId(), request.model(), decidedBy);
		return new Outcome(pending, response);
	}

	/** Reject: mark rejected with a reason. Never forwarded. */
	@Transactional
	public PendingApproval reject(PendingApproval pending, String decidedBy, String reason) {
		requirePending(pending);
		pending.decide(PendingApproval.State.rejected, decidedBy,
				reason != null && !reason.isBlank() ? reason : "rejected", Instant.now());
		PendingApproval saved = repository.save(pending);
		log.info("approval rejected id={} decidedBy={} reason=\"{}\"", saved.getId(), decidedBy,
				saved.getDecisionReason());
		return saved;
	}

	/** Auto-reject on TTL expiry. Never forwarded. */
	@Transactional
	public PendingApproval expire(PendingApproval pending) {
		requirePending(pending);
		pending.decide(PendingApproval.State.expired, "system", "TTL expired", Instant.now());
		PendingApproval saved = repository.save(pending);
		log.info("approval expired id={} expiredAt={}", saved.getId(), saved.getExpiresAt());
		return saved;
	}

	private void requirePending(PendingApproval pending) {
		if (pending.getState() != PendingApproval.State.pending) {
			throw new NotPendingException(pending.getId(), pending.getState());
		}
	}

	private static LedgerContext ledgerOf(PendingApproval p) {
		return new LedgerContext(p.getTenantId(), p.getTeamId(), p.getProjectId(), p.getUserId(),
				p.getEnvironment(), p.getIdempotencyKey());
	}
}
