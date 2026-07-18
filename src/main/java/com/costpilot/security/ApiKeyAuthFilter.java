package com.costpilot.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 6.1: authenticates each request from its API key.
 *
 * Accepts the key as {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. On a
 * valid key it installs an {@link AuthenticatedPrincipal} into the SecurityContext (with
 * ROLE_ADMIN for a tenant-admin key) and exposes it as a request attribute so controllers
 * can read the resolved identity. A missing/unknown/revoked key leaves the context
 * unauthenticated; SecurityConfig then rejects protected paths with 401.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	public static final String PRINCIPAL_ATTRIBUTE = "costpilot.principal";
	public static final String ROLE_ADMIN = "ROLE_ADMIN";
	public static final String ROLE_TEAM = "ROLE_TEAM";

	private static final String API_KEY_HEADER = "X-API-Key";
	private static final String BEARER_PREFIX = "Bearer ";

	private final ApiKeyAuthService authService;

	public ApiKeyAuthFilter(ApiKeyAuthService authService) {
		this.authService = authService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String rawKey = extractKey(request);
		if (rawKey != null) {
			try {
				AuthenticatedPrincipal principal = authService.authenticate(rawKey);
				String role = principal.admin() ? ROLE_ADMIN : ROLE_TEAM;
				var authentication = new UsernamePasswordAuthenticationToken(
						principal, null, List.of(new SimpleGrantedAuthority(role)));
				SecurityContextHolder.getContext().setAuthentication(authentication);
				request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
			} catch (InvalidApiKeyException e) {
				// leave the context unauthenticated - the entry point returns 401 for
				// protected paths; permitted paths (health/prometheus/mock) still pass
				SecurityContextHolder.clearContext();
			}
		}
		chain.doFilter(request, response);
	}

	private static String extractKey(HttpServletRequest request) {
		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
			return authorization.substring(BEARER_PREFIX.length()).trim();
		}
		String apiKey = request.getHeader(API_KEY_HEADER);
		return apiKey != null && !apiKey.isBlank() ? apiKey.trim() : null;
	}
}
