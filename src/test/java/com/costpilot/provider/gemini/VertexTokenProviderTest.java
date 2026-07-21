package com.costpilot.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.auth.oauth2.AccessToken;

class VertexTokenProviderTest {

	// ADC tokens are short-lived; the hot path must not fetch per request, so a valid
	// token is cached and reused until it nears expiry.
	@Test
	void cachesTokenUntilItNearsExpiry() {
		AtomicInteger fetches = new AtomicInteger();
		Instant now = Instant.parse("2026-07-21T00:00:00Z");
		VertexTokenProvider provider = new VertexTokenProvider(
				() -> new AccessToken("tok-" + fetches.incrementAndGet(), Date.from(now.plusSeconds(3600))),
				Duration.ofSeconds(60), () -> now);

		assertThat(provider.getAccessToken()).isEqualTo("tok-1");
		assertThat(provider.getAccessToken()).isEqualTo("tok-1");
		assertThat(fetches.get()).isEqualTo(1);
	}

	// once the cached token falls inside the refresh-skew window it is proactively
	// re-fetched, so a request never rides an about-to-expire token.
	@Test
	void refreshesBeforeExpiryWithinSkewWindow() {
		AtomicInteger fetches = new AtomicInteger();
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-21T00:00:00Z"));
		VertexTokenProvider provider = new VertexTokenProvider(
				() -> new AccessToken("tok-" + fetches.incrementAndGet(), Date.from(now.get().plusSeconds(100))),
				Duration.ofSeconds(60), now::get);

		assertThat(provider.getAccessToken()).isEqualTo("tok-1"); // fresh token, expires at +100s
		now.set(now.get().plusSeconds(50)); // now +50s -> token expires in 50s, inside the 60s skew
		assertThat(provider.getAccessToken()).isEqualTo("tok-2"); // proactively refreshed
		assertThat(fetches.get()).isEqualTo(2);
	}

	// a token with no expiry (defensive: some sources omit it) is never trusted as cached.
	@Test
	void refetchesWhenTokenHasNoExpiry() {
		AtomicInteger fetches = new AtomicInteger();
		Instant now = Instant.parse("2026-07-21T00:00:00Z");
		VertexTokenProvider provider = new VertexTokenProvider(
				() -> new AccessToken("tok-" + fetches.incrementAndGet(), null),
				Duration.ofSeconds(60), () -> now);

		assertThat(provider.getAccessToken()).isEqualTo("tok-1");
		assertThat(provider.getAccessToken()).isEqualTo("tok-2");
		assertThat(fetches.get()).isEqualTo(2);
	}
}
