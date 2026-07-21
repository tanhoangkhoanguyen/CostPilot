package com.costpilot.provider.gemini;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;

import com.google.auth.oauth2.AccessToken;

/**
 * 11.1: supplies short-lived OAuth2 bearer tokens for Vertex AI from GCP Application
 * Default Credentials. Caches the current {@link AccessToken} and refreshes it before
 * expiry so the budget hot path never pays a per-request auth round-trip, and no
 * long-lived secret is baked into the image.
 *
 * <p>The token {@link Source} is injected so the ADC round-trip (and its I/O failure
 * modes) stay out of the caching logic - unit tests drive caching/refresh with a fake
 * source and a controllable clock.
 */
public class VertexTokenProvider {

	/** Fetches a fresh access token, e.g. from {@code GoogleCredentials.refreshAccessToken()}. */
	@FunctionalInterface
	public interface Source {
		AccessToken fetch();
	}

	private final Source source;
	private final Duration refreshSkew;
	private final Supplier<Instant> clock;
	private AccessToken cached;

	public VertexTokenProvider(Source source) {
		this(source, Duration.ofSeconds(60), Instant::now);
	}

	VertexTokenProvider(Source source, Duration refreshSkew, Supplier<Instant> clock) {
		this.source = source;
		this.refreshSkew = refreshSkew;
		this.clock = clock;
	}

	/** The current bearer token value, fetching or refreshing if none is cached or it nears expiry. */
	public synchronized String getAccessToken() {
		if (cached == null || expiringSoon(cached)) {
			cached = source.fetch();
		}
		return cached.getTokenValue();
	}

	private boolean expiringSoon(AccessToken token) {
		Date expiry = token.getExpirationTime();
		if (expiry == null) {
			return true; // no expiry to trust - always refetch
		}
		return !clock.get().isBefore(expiry.toInstant().minus(refreshSkew));
	}
}
