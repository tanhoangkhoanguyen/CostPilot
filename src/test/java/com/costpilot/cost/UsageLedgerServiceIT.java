package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.Usage;
import com.costpilot.domain.UsageRecordRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UsageLedgerServiceIT {

	@Autowired
	private UsageLedgerService ledger;

	@Autowired
	private UsageRecordRepository repository;

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private LedgerContext context(String key) {
		return new LedgerContext(null, "team-a", "project-x", "user-1", "dev", key);
	}

	private Cost cost(String in, String out) {
		return new Cost(new BigDecimal(in), new BigDecimal(out));
	}

	@Test
	void replayingTheSameIdempotencyKeyDoesNotDoubleCount() {
		String key = "replay-" + UUID.randomUUID();

		ledger.record(context(key), "openai", "gpt-4o-mini", new Usage(2000, 1000), cost("0.0003", "0.0006"));
		ledger.record(context(key), "openai", "gpt-4o-mini", new Usage(2000, 1000), cost("0.0003", "0.0006"));
		ledger.record(context(key), "openai", "gpt-4o-mini", new Usage(2000, 1000), cost("0.0003", "0.0006"));

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.totalCost()).isEqualByComparingTo("0.0009");
	}

	@Test
	void concurrentWritesWithTheSameKeyRecordExactlyOnce() throws Exception {
		String key = "race-" + UUID.randomUUID();
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					start.await();
					ledger.record(context(key), "openai", "gpt-4o-mini",
							new Usage(2000, 1000), cost("0.0003", "0.0006"));
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.totalCost()).isEqualByComparingTo("0.0009");
	}

	@Test
	void concurrentWritesWithDistinctKeysAllLand() throws Exception {
		int writes = 32;
		CountDownLatch start = new CountDownLatch(1);
		List<String> keys = IntStream.range(0, writes).mapToObj(i -> "bulk-" + i + "-" + UUID.randomUUID()).toList();
		try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
			for (String key : keys) {
				pool.submit(() -> {
					start.await();
					ledger.record(context(key), "openai", "gpt-4o-mini",
							new Usage(1000, 1000), cost("0.00015", "0.0006"));
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(repository.count()).isEqualTo(writes);
		// 32 x 0.00075 = 0.024 - the ledger sum reconciles with per-request costs
		assertThat(repository.totalCost()).isEqualByComparingTo("0.024");
	}
}
