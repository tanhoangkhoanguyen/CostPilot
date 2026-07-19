package com.costpilot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 6.1: stateless API-key security.
 *
 * Everything is authenticated except the operational/dev surfaces that must stay open:
 *  - /actuator/health, /actuator/prometheus  (k8s probes + Prometheus scrape)
 *  - /mock/**                                 (embedded mock upstream the app calls itself)
 *
 * No sessions, no CSRF (there are no browser forms - this is a machine-to-machine gateway).
 * The ApiKeyAuthFilter runs before the username/password filter and installs the resolved
 * principal; an unauthenticated protected request gets a bare 401 from the entry point.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthService authService) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// only authorize the initial REQUEST dispatch, not ASYNC/ERROR/FORWARD.
						// the gateway streams via SseEmitter (MVC async); re-authorizing the
						// ASYNC dispatch under the stateless (empty) context would deny the
						// already-authorized request and sever the stream mid-flight
						// ("Connection prematurely closed DURING response").
						.shouldFilterAllDispatcherTypes(false)
						.requestMatchers("/actuator/health", "/actuator/health/**",
								"/actuator/prometheus", "/mock/**")
						.permitAll()
						// admin-only control plane: key minting (6.1) + governance config and
						// approvals (9.x) require a tenant-admin key. /admin/audit stays out of
						// this list on purpose - it is team-scoped read for non-admins too.
						.requestMatchers("/admin/keys/**", "/admin/budgets/**", "/admin/policies/**",
								"/admin/approvals/**")
						.hasRole("ADMIN")
						.anyRequest().authenticated())
				.exceptionHandling(e -> e
						// unauthenticated -> 401; authenticated-but-forbidden (e.g. a team
						// key hitting /admin/keys) -> 403. Set both explicitly so the
						// distinction is deterministic across Spring Security versions.
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
						.accessDeniedHandler((request, response, ex) ->
								response.sendError(HttpStatus.FORBIDDEN.value())))
				.addFilterBefore(new ApiKeyAuthFilter(authService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
