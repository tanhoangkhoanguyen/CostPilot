package com.costpilot.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.Usage;
import com.costpilot.cost.Cost;
import com.costpilot.cost.LedgerContext;
import com.costpilot.cost.UsageLedgerService;
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.domain.UsageRecordRepository;

// 3.1 acceptance: counters reconcile with the ledger, stay correct under
// concurrent charges, and rebuild from the ledger on cold start.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BudgetCountersIT {

	@Autowired
	private BudgetService budgetService;

	@Autowired
	private UsageLedgerService ledger;

	@Autowired
	private BudgetRepository budgets;

	@Autowired
	private UsageRecordRepository usageRepository;

	@Autowired
	private StringRedisTemplate redis;

	private String team;

	@BeforeEach
	void freshScope() {
		// unique scope per test - the shared context accumulates state otherwise
		team = "team-" + UUID.randomUUID();
		budgets.save(new Budget("team", team, new BigDecimal("10.000000000")));
	}

	private LedgerContext context(String key) {
		return new LedgerContext(null, team, null, null, null, key);
	}

	private void chargeViaLedger(String key, String in, String out) {
		ledger.record(context(key), "openai", "gpt-4o-mini",
				new Usage(1000, 1000), new Cost(new BigDecimal(in), new BigDecimal(out)), null);
	}

	@Test
	void counterReconcilesWithTheLedger() {
		chargeViaLedger("r1-" + team, "0.0003", "0.0006");
		chargeViaLedger("r2-" + team, "0.001", "0.002");

		BigDecimal remaining = budgetService.remaining(BudgetScope.TEAM, team);
		BigDecimal ledgerSpent = budgetService.spentFromLedger(BudgetScope.TEAM, team);

		assertThat(ledgerSpent).isEqualByComparingTo("0.0039");
		assertThat(remaining).isEqualByComparingTo(new BigDecimal("10").subtract(ledgerSpent));
	}

	@Test
	void atomicUnderConcurrentChargesNoLostUpdates() throws Exception {
		int writers = 24;
		// prime the counter so every concurrent path takes the atomic DECRBY branch
		budgetService.remaining(BudgetScope.TEAM, team);

		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
			for (int i = 0; i < writers; i++) {
				String key = "conc-" + i + "-" + team;
				pool.submit(() -> {
					start.await();
					chargeViaLedger(key, "0.0001", "0.0002");
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		}

		// 24 x 0.0003 = 0.0072 - exact integer nanodollar math, no drift, no lost update
		assertThat(budgetService.remaining(BudgetScope.TEAM, team)).isEqualByComparingTo("9.9928");
		assertThat(budgetService.spentFromLedger(BudgetScope.TEAM, team)).isEqualByComparingTo("0.0072");
	}

	@Test
	void counterRebuildsFromLedgerOnColdStart() {
		chargeViaLedger("cold-1-" + team, "0.5", "0.25");
		chargeViaLedger("cold-2-" + team, "0.1", "0.05");

		// cold start: Redis lost everything
		redis.delete(BudgetService.counterKey(BudgetScope.TEAM, team));

		BigDecimal rebuilt = budgetService.remaining(BudgetScope.TEAM, team);
		assertThat(rebuilt).isEqualByComparingTo("9.1"); // 10 - 0.9

		// and the very next charge keeps counting from the rebuilt value
		chargeViaLedger("cold-3-" + team, "0.05", "0.05");
		assertThat(budgetService.remaining(BudgetScope.TEAM, team)).isEqualByComparingTo("9.0");
	}

	@Test
	void replayedLedgerWritesDoNotMoveTheCounterTwice() {
		budgetService.remaining(BudgetScope.TEAM, team); // prime
		String key = "replay-" + team;
		chargeViaLedger(key, "0.001", "0.001");
		chargeViaLedger(key, "0.001", "0.001");
		chargeViaLedger(key, "0.001", "0.001");

		assertThat(usageRepository.findByIdempotencyKey(key)).isPresent();
		assertThat(budgetService.remaining(BudgetScope.TEAM, team)).isEqualByComparingTo("9.998");
	}
}
