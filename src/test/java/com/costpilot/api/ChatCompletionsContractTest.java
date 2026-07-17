package com.costpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.costpilot.budget.BudgetGuard;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.policy.PolicyDecision;
import com.costpilot.policy.PolicyService;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.upstream.ForwardingService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebMvcTest(ChatCompletionsController.class)
class ChatCompletionsContractTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ForwardingService forwardingService;

	@MockitoBean
	private BudgetGuard budgetGuard;

	@MockitoBean
	private PolicyService policyService;

	@MockitoBean
	private com.costpilot.budget.DowngradeService downgradeService;

	@MockitoBean
	private com.costpilot.budget.BudgetService budgetService;

	@org.junit.jupiter.api.BeforeEach
	void guardAndPolicyAllowByDefault() {
		when(budgetGuard.reserve(any(), any()))
				.thenReturn(new BudgetGuard.GuardResult(java.util.List.of(), null, false));
		when(policyService.evaluate(any(), any()))
				.thenAnswer(inv -> PolicyDecision.allowDefault(inv.getArgument(1)));
	}

	private static final String VALID_BODY = """
			{
			  "model": "gpt-4o-mini",
			  "messages": [
			    {"role": "system", "content": "you are a helpful assistant"},
			    {"role": "user", "content": "hello costpilot"}
			  ],
			  "stream": false,
			  "max_tokens": 128
			}
			""";

	@Test
	void nonStreamingRelaysUpstreamJson() throws Exception {
		when(forwardingService.forward(any(), any())).thenReturn(Mono.just(new CanonicalChatResponse(
				"chatcmpl-1", "gpt-4o-mini", "hello costpilot", "stop", new Usage(9, 2))));

		mockMvc.perform(post("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-Team-ID", "team-a")
				.header("X-Project-ID", "project-x")
				.content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.object").value("chat.completion"))
				.andExpect(jsonPath("$.choices[0].message.content").value("hello costpilot"))
				.andExpect(jsonPath("$.usage.total_tokens").value(11));
	}

	@Test
	void streamingRelaysSseChunksEndingWithDone() throws Exception {
		when(forwardingService.forwardStream(any(), any(), any())).thenReturn(Flux.just(
				CanonicalStreamChunk.role("assistant"),
				CanonicalStreamChunk.content("hello "),
				CanonicalStreamChunk.content("costpilot"),
				CanonicalStreamChunk.finish("stop"),
				CanonicalStreamChunk.endOfStream()));
		String body = VALID_BODY.replace("\"stream\": false", "\"stream\": true");

		MvcResult started = mockMvc.perform(post("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(request().asyncStarted())
				.andReturn();

		MvcResult result = mockMvc.perform(asyncDispatch(started))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
				.andReturn();

		String sse = result.getResponse().getContentAsString();
		assertThat(sse).contains("chat.completion.chunk");
		assertThat(sse).contains("\"content\":\"hello \"");
		assertThat(sse).contains("\"finish_reason\":\"stop\"");
		assertThat(sse.trim()).endsWith("data:[DONE]");
	}

	@Test
	void missingModelReturns400WithClearError() throws Exception {
		String body = """
				{"messages": [{"role": "user", "content": "hi"}]}
				""";

		mockMvc.perform(post("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.type").value("invalid_request_error"))
				.andExpect(jsonPath("$.error.message").value("model: model must not be blank"));
	}

	@Test
	void emptyMessagesReturns400() throws Exception {
		String body = """
				{"model": "gpt-4o-mini", "messages": []}
				""";

		mockMvc.perform(post("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value("messages: messages must not be empty"));
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mockMvc.perform(post("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.type").value("invalid_request_error"));
	}
}
