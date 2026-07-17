package com.costpilot.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.domain.UsageRecordRepository;

/**
 * Live remaining-budget counters in Redis, one per active budget.
 *
 * Counters are integer NANODOLLARS (cost scale is capped at 9 by the schema), so
 * updates use plain DECRBY - exact integer math, atomic, no INCRBYFLOAT
 * floating-point drift. Postgres stays the source of truth: a counter can be
 * rebuilt from limit - ledger spend at any time (cold start, drift repair).
 */
@Service
public class BudgetService {

	private static final Logger log = LoggerFactory.getLogger(BudgetService.class);
	private static final BigDecimal NANOS = BigDecimal.TEN.pow(9);

	// decrement only if the counter exists; a missing counter means "rebuild first"
	private static final DefaultRedisScript<Long> CHARGE_IF_PRESENT = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 1 then
				return redis.call('DECRBY', KEYS[1], ARGV[1])
			end
			return nil
			""", Long.class);

	private final StringRedisTemplate redis;
	private final BudgetRepository budgets;
	private final UsageRecordRepository usage;

	public BudgetService(StringRedisTemplate redis, BudgetRepository budgets, UsageRecordRepository usage) {
		this.redis = redis;
		this.budgets = budgets;
		this.usage = usage;
	}

	public static String counterKey(BudgetScope scope, String ref) {
		return "budget:remaining:" + scope.dbValue() + ":" + ref;
	}

	/**
	 * Atomically deduct a charge from the live counter of every scope it hits.
	 * Called once per fresh ledger insert - ledger idempotency is what keeps
	 * these counters replay-safe.
	 */
	public void charge(BudgetScope scope, String ref, BigDecimal amount) {
		if (ref == null || ref.isBlank()) {
			return;
		}
		Optional<Budget> budget = budgets.findByScopeTypeAndScopeRefAndActiveTrue(scope.dbValue(), ref);
		if (budget.isEmpty()) {
			return; // nothing governs this scope - no counter to maintain
		}
		String key = counterKey(scope, ref);
		Long remaining = redis.execute(CHARGE_IF_PRESENT, List.of(key), Long.toString(toNanos(amount)));
		if (remaining == null) {
			// cold start: the ledger row for this charge is already committed, so a
			// rebuild from the ledger includes it - do NOT also decrement
			BigDecimal rebuilt = rebuild(scope, ref);
			log.info("budget counter cold-start rebuild scope={} ref={} remaining={}",
					scope.dbValue(), ref, rebuilt.toPlainString());
			return;
		}
		log.debug("budget charge scope={} ref={} amount={} remaining={}",
				scope.dbValue(), ref, amount.toPlainString(), fromNanos(remaining).toPlainString());
	}

	/** Live remaining for a scope; rebuilds from the ledger if the counter is gone. */
	public BigDecimal remaining(BudgetScope scope, String ref) {
		String value = redis.opsForValue().get(counterKey(scope, ref));
		if (value != null) {
			return fromNanos(Long.parseLong(value));
		}
		return rebuild(scope, ref);
	}

	/**
	 * Recompute remaining = limit - ledger spend and install it. SETNX so a
	 * concurrent rebuild/charge that got there first is never clobbered.
	 */
	public BigDecimal rebuild(BudgetScope scope, String ref) {
		Budget budget = budgets.findByScopeTypeAndScopeRefAndActiveTrue(scope.dbValue(), ref)
				.orElseThrow(() -> new IllegalArgumentException(
						"no active budget for scope=" + scope.dbValue() + " ref=" + ref));
		BigDecimal spent = spentFromLedger(scope, ref);
		BigDecimal remaining = budget.getLimitAmount().subtract(spent);
		String key = counterKey(scope, ref);
		redis.opsForValue().setIfAbsent(key, Long.toString(toNanos(remaining)));
		return fromNanos(Long.parseLong(redis.opsForValue().get(key)));
	}

	/** Ledger truth for a scope - what reconciliation checks counters against. */
	public BigDecimal spentFromLedger(BudgetScope scope, String ref) {
		return switch (scope) {
			case TENANT -> usage.totalCostForTenant(ref);
			case TEAM -> usage.totalCostForTeam(ref);
			case PROJECT -> usage.totalCostForProject(ref);
			case MODEL -> usage.totalCostForModel(ref);
		};
	}

	static long toNanos(BigDecimal amount) {
		return amount.setScale(9, RoundingMode.HALF_UP).multiply(NANOS).longValueExact();
	}

	static BigDecimal fromNanos(long nanos) {
		return BigDecimal.valueOf(nanos).divide(NANOS);
	}
}
