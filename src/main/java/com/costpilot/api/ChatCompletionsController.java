package com.costpilot.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.costpilot.api.dto.ChatCompletionRequest;
import com.costpilot.upstream.ForwardingService;

import jakarta.validation.Valid;
import reactor.core.Disposable;

@RestController
public class ChatCompletionsController {

	private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

	private final ForwardingService forwardingService;

	public ChatCompletionsController(ForwardingService forwardingService) {
		this.forwardingService = forwardingService;
	}

	@PostMapping(value = "/v1/chat/completions", produces = { MediaType.APPLICATION_JSON_VALUE,
			MediaType.TEXT_EVENT_STREAM_VALUE })
	public Object chatCompletions(
			@Valid @RequestBody ChatCompletionRequest request,
			@RequestHeader(value = "X-Team-ID", required = false) String teamId,
			@RequestHeader(value = "X-Project-ID", required = false) String projectId) {

		RequestContext context = RequestContext.of(teamId, projectId);
		log.info("chat.completions team={} project={} model={} stream={}",
				context.teamId(), context.projectId(), request.model(), request.isStreaming());

		if (request.isStreaming()) {
			return relayStream(request);
		}
		String upstreamBody = forwardingService.forward(request).block();
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(upstreamBody);
	}

	private SseEmitter relayStream(ChatCompletionRequest request) {
		SseEmitter emitter = new SseEmitter(0L);
		Disposable subscription = forwardingService.forwardStream(request).subscribe(
				data -> {
					try {
						emitter.send(SseEmitter.event().data(data));
					} catch (Exception e) {
						emitter.completeWithError(e);
					}
				},
				emitter::completeWithError,
				emitter::complete);
		// client went away -> stop pulling from the upstream
		emitter.onCompletion(subscription::dispose);
		emitter.onTimeout(subscription::dispose);
		emitter.onError(t -> subscription.dispose());
		return emitter;
	}
}
