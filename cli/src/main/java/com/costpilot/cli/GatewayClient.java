package com.costpilot.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP client to the CostPilot admin API, built on the JDK HttpClient. Carries the
 * admin bearer key on every request. Non-2xx responses raise {@link ApiException} so the
 * CLI can exit non-zero with the server's message.
 */
public class GatewayClient {

	public static class ApiException extends RuntimeException {
		private final int status;

		public ApiException(int status, String body) {
			super("HTTP " + status + ": " + body);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();
	private final String baseUrl;
	private final String adminKey;

	public GatewayClient(String baseUrl, String adminKey) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.adminKey = adminKey;
	}

	public String get(String path) {
		return send(request(path).GET().build());
	}

	public String put(String path, String jsonBody) {
		return send(request(path)
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
	}

	public String post(String path, String jsonBody) {
		HttpRequest.BodyPublisher body = jsonBody == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(jsonBody);
		HttpRequest.Builder b = request(path);
		if (jsonBody != null) {
			b.header("Content-Type", "application/json");
		}
		return send(b.POST(body).build());
	}

	public String delete(String path) {
		return send(request(path).DELETE().build());
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + adminKey);
	}

	private String send(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(response.statusCode(), response.body());
			}
			return response.body();
		} catch (java.io.IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException("request to " + baseUrl + " failed: " + e.getMessage(), e);
		}
	}
}
