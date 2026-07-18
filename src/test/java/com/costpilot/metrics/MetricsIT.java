package com.costpilot.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.security.AuthTestSupport;

// 6.2 acceptance: metrics are scrapeable at /actuator/prometheus, and a budget rejection
// increments its counter (the value a Grafana panel reads). Drives a real 402 then scrapes.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MetricsIT {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private BudgetRepository budgets;

	private static final String BODY = """
			{
			  "model": "gpt-4o-mini",
			  "messages": [{"role": "user", "content": "hello costpilot"}],
			  "stream": false,
			  "max_tokens": %d
			}
			""";

	private ResponseEntity<String> post(String team, int maxTokens) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(AuthTestSupport.ADMIN_KEY);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("X-Team-ID", team);
		return restTemplate.exchange("/v1/chat/completions", HttpMethod.POST,
				new HttpEntity<>(BODY.formatted(maxTokens), headers), String.class);
	}

	private String scrape() {
		// prometheus endpoint is permitted (unauthenticated) - it must be, so a scraper
		// with no API key can read it
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	@Test
	void prometheusEndpointIsScrapeableWithoutAuth() {
		String body = scrape();
		// the registry is live and exporting our governance meters + JVM basics
		assertThat(body).contains("costpilot_budget_guard_seconds");
		assertThat(body).contains("jvm_memory_used_bytes");
	}

	@Test
	void budgetRejectionIncrementsItsCounter() {
		double before = counterValue(scrape(), "costpilot_budget_rejections_total");

		// tiny cap + large max_tokens: the estimate exceeds the cap and no cheaper
		// allowed model fits, so the request is rejected with 402
		String team = "metrics-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, new BigDecimal("0.0000001")));
		ResponseEntity<String> response = post(team, 4096);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);

		double after = counterValue(scrape(), "costpilot_budget_rejections_total");
		assertThat(after).isGreaterThan(before);
	}

	// pull a single counter's value out of the Prometheus text exposition format.
	// counters may carry tags {application="costpilot",...}; match the metric name at
	// line start and take the trailing numeric value.
	private static double counterValue(String prometheus, String metric) {
		double sum = 0;
		boolean seen = false;
		for (String line : prometheus.split("\n")) {
			if (line.startsWith("#") || !line.startsWith(metric)) {
				continue;
			}
			// skip the client's _created timestamp series (not a count)
			if (line.startsWith(metric + "_created")) {
				continue;
			}
			// the char right after the metric name must be a tag brace or a space,
			// so we don't match a longer metric that shares this prefix
			char next = line.charAt(metric.length());
			if (next != ' ' && next != '{') {
				continue;
			}
			// name and optional {tags} then a space then the value
			int space = line.lastIndexOf(' ');
			if (space > 0) {
				sum += Double.parseDouble(line.substring(space + 1).trim());
				seen = true;
			}
		}
		return seen ? sum : 0;
	}
}
