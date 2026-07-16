package com.costpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CostpilotApplicationTests {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void healthReturns200() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"status\":\"UP\"");
	}

	@Test
	void requestThreadsAreVirtual() {
		ResponseEntity<String> response = restTemplate.getForEntity("/test/thread-info", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).startsWith("virtual=true");
	}

	@TestConfiguration
	static class ThreadInfoTestConfig {
		@Bean
		ThreadInfoController threadInfoController() {
			return new ThreadInfoController();
		}
	}

	@RestController
	static class ThreadInfoController {
		@GetMapping("/test/thread-info")
		String threadInfo() {
			Thread current = Thread.currentThread();
			return "virtual=" + current.isVirtual() + " name=" + current;
		}
	}
}
