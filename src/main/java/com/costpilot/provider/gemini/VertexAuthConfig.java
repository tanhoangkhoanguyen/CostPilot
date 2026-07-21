package com.costpilot.provider.gemini;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

/**
 * 11.1: wires the Vertex AI ADC token source. The Application Default Credentials
 * lookup is lazy - it runs on the first token fetch, which only happens on a REAL
 * Vertex-flavor request. Mock/dev/test startup never touches ADC, so no credential is
 * needed to boot and none is baked into the image.
 *
 * <p>ADC resolves identically from {@code gcloud auth application-default login}, a
 * service-account JSON via {@code GOOGLE_APPLICATION_CREDENTIALS}, or GCE/Workload
 * Identity metadata.
 */
@Configuration
public class VertexAuthConfig {

	private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

	@Bean
	VertexTokenProvider vertexTokenProvider() {
		return new VertexTokenProvider(VertexAuthConfig::fetchAdcToken);
	}

	private static AccessToken fetchAdcToken() {
		try {
			GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
					.createScoped(List.of(CLOUD_PLATFORM_SCOPE));
			credentials.refreshIfExpired();
			return credentials.getAccessToken();
		} catch (IOException e) {
			throw new UncheckedIOException("failed to obtain Vertex ADC access token", e);
		}
	}
}
