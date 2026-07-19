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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.costpilot.budget.BudgetGuard;
import com.costpilot.core.model.CanonicalChatResponse;
import com.costpilot.policy.PolicyDecision;
import com.costpilot.policy.PolicyService;
import com.costpilot.core.model.CanonicalStreamChunk;
import com.costpilot.core.model.Usage;
import com.costpilot.security.AuthenticatedPrincipal;
import com.costpilot.upstream.ForwardingService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.test.context.TestConfiguration;

import reactor.core.publisher.Flux;

// 6.1: this is a controller contract slice - it mocks the governance beans and does NOT
// exercise the real API-key filter (that's covered by AuthIT). Security is on the
// classpath, so the slice needs a permissive chain plus an injected principal, since the
// controller now reads identity from CurrentPrincipal instead of trusting X-Team-ID.
@WebMvcTest(ChatCompletionsController.class)
@Import(ChatCompletionsContractTest.PermitAllSecurity.class)
class ChatCompletionsContractTest {

	@TestConfiguration
	static class PermitAllSecurity {
		@Bean
		SecurityFilterChain testChain(HttpSecurity http) throws Exception {
			http.csrf(AbstractHttpConfigurer::disable)
					.authorizeHttpRequests(a -> a.anyRequest().permitAll());
			return http.build();
		}
	}

	// an admin principal so the tests' X-Team-ID='team-a' impersonation resolves as before
	private static Authentication adminPrincipal() {
		AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
				"acme", "platform", "chatbot", java.util.UUID.randomUUID(), true);
		return new UsernamePasswordAuthenticationToken(principal, null,
				java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ForwardingService forwardingService;

	@MockitoBean
	private BudgetGuard budgetGuard;

	@MockitoBean
	private PolicyService policyService;

	@MockitoBean
	private com.costpilot.budget.BudgetService budgetService;

	@MockitoBean
	private com.costpilot.cost.AuditService auditService;

	@MockitoBean
	private com.costpilot.metrics.GovernanceMetrics metrics;

	// Stage 8 / the route+budget pipeline now live behind the executor; the controller
	// delegates to it for non-streaming and uses prepare() for streaming. The approval
	// service is only touched on a REQUIRE_APPROVAL verdict (not in these ALLOW tests).
	@MockitoBean
	private GovernedRequestExecutor executor;

	@MockitoBean
	private com.costpilot.approval.PendingApprovalService approvalService;

	@org.junit.jupiter.api.BeforeEach
	void guardAndPolicyAllowByDefault() {
		when(policyService.evaluate(any(), any()))
				.thenAnswer(inv -> PolicyDecision.allowDefault(inv.getArgument(1)));
		// streaming path: prepare() returns a no-op reservation so relayStream runs
		when(executor.prepare(any(), any(), any(), any(), any()))
				.thenAnswer(inv -> new GovernedRequestExecutor.Prepared(inv.getArgument(0), inv.getArgument(1),
						new BudgetGuard.GuardResult(java.util.List.of(), null, false)));
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
		// the controller delegates non-streaming execution to the executor, which renders
		// the OpenAI-shaped response. Reuse the real render() so the contract is exercised.
		GovernedRequestExecutor realRender = new GovernedRequestExecutor(null, null, null, null, null, null);
		when(executor.executeNonStreaming(any(), any(), any(), any(), any()))
				.thenAnswer(inv -> realRender.render(inv.getArgument(0), new CanonicalChatResponse(
						"chatcmpl-1", "gpt-4o-mini", "hello costpilot", "stop", new Usage(9, 2))));

		mockMvc.perform(post("/v1/chat/completions")
				.with(authentication(adminPrincipal()))
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
				.with(authentication(adminPrincipal()))
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
