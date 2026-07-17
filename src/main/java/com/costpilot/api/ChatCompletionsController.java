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
import com.costpilot.budget.BudgetGuard;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.cost.LedgerContext;
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

	public ChatCompletionsController(ForwardingService forwardingService, BudgetGuard budgetGuard) {
		this.forwardingService = forwardingService;
		this.budgetGuard = budgetGuard;
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

		// hot-path budget check: hard-block throws 402 out of here; soft limit
		// serves the request with a warning header (3.2)
		BudgetGuard.GuardResult guard = budgetGuard.reserve(canonical, ledgerContext);
		if (guard.warning() != null) {
			servletResponse.setHeader("X-CostPilot-Budget-Warning", guard.warning());
		}

		if (canonical.stream()) {
			return relayStream(canonical, ledgerContext, guard);
		}
		try {
			CanonicalChatResponse upstream = forwardingService.forward(canonical, ledgerContext).block();
			return render(canonical, upstream);
		} finally {
			budgetGuard.release(guard);
		}
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

	private SseEmitter relayStream(CanonicalChatRequest request, LedgerContext ledgerContext,
			BudgetGuard.GuardResult guard) {
		String id = "chatcmpl-" + UUID.randomUUID();
		long created = Instant.now().getEpochSecond();
		SseEmitter emitter = new SseEmitter(0L);
		// some providers (gemini) have no [DONE] sentinel - the gateway's public
		// contract always ends with one, whether the adapter signals done or the
		// upstream flux just completes
		java.util.concurrent.atomic.AtomicBoolean doneSent = new java.util.concurrent.atomic.AtomicBoolean();
		java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
		Disposable subscription = forwardingService.forwardStream(request, ledgerContext).subscribe(
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
