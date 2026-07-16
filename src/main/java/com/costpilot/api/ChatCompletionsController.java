package com.costpilot.api;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
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

import jakarta.validation.Valid;

@RestController
public class ChatCompletionsController {

	private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

	@PostMapping(value = "/v1/chat/completions", produces = { MediaType.APPLICATION_JSON_VALUE,
			MediaType.TEXT_EVENT_STREAM_VALUE })
	public Object chatCompletions(
			@Valid @RequestBody ChatCompletionRequest request,
			@RequestHeader(value = "X-Team-ID", required = false) String teamId,
			@RequestHeader(value = "X-Project-ID", required = false) String projectId) throws IOException {

		RequestContext context = RequestContext.of(teamId, projectId);
		log.info("chat.completions team={} project={} model={} stream={}",
				context.teamId(), context.projectId(), request.model(), request.isStreaming());

		String id = "chatcmpl-" + UUID.randomUUID();
		long created = Instant.now().getEpochSecond();
		String echo = request.messages().get(request.messages().size() - 1).content();

		if (request.isStreaming()) {
			return streamEcho(id, created, request.model(), echo);
		}
		return echoResponse(id, created, request.model(), echo);
	}

	private ChatCompletionResponse echoResponse(String id, long created, String model, String echo) {
		return new ChatCompletionResponse(
				id, "chat.completion", created, model,
				List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", echo), "stop")),
				new ChatCompletionResponse.Usage(0, 0, 0));
	}

	private SseEmitter streamEcho(String id, long created, String model, String echo) throws IOException {
		SseEmitter emitter = new SseEmitter();
		// Sends before the emitter is returned are buffered and flushed on dispatch,
		// so the whole echo can be emitted inline.
		emitter.send(SseEmitter.event().data(
				chunk(id, created, model, new ChatCompletionChunk.Delta("assistant", null), null),
				MediaType.APPLICATION_JSON));
		for (String token : echo.split("(?<= )")) {
			emitter.send(SseEmitter.event().data(
					chunk(id, created, model, new ChatCompletionChunk.Delta(null, token), null),
					MediaType.APPLICATION_JSON));
		}
		emitter.send(SseEmitter.event().data(
				chunk(id, created, model, new ChatCompletionChunk.Delta(null, null), "stop"),
				MediaType.APPLICATION_JSON));
		emitter.send(SseEmitter.event().data("[DONE]"));
		emitter.complete();
		return emitter;
	}

	private ChatCompletionChunk chunk(String id, long created, String model,
			ChatCompletionChunk.Delta delta, String finishReason) {
		return new ChatCompletionChunk(id, "chat.completion.chunk", created, model,
				List.of(new ChatCompletionChunk.ChunkChoice(0, delta, finishReason)));
	}
}
