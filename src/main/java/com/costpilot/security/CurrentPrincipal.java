package com.costpilot.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 6.1: read the authenticated principal from the SecurityContext. Any path that reaches a
 * governed controller has passed the filter chain, so the principal is always present -
 * a null here means the security config let something through it shouldn't have.
 */
public final class CurrentPrincipal {

	private CurrentPrincipal() {
	}

	public static AuthenticatedPrincipal require() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
			throw new InvalidApiKeyException("no authenticated principal");
		}
		return principal;
	}
}
