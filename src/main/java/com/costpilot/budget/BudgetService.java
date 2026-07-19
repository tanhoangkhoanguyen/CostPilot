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

	/** Budget limit mirrored next to the counter so the guard's Lua stays Redis-only. */
	public static String limitKey(BudgetScope scope, String ref) {
		return "budget:limit:" + scope.dbValue() + ":" + ref;
	}

	/** Short-lived negative cache: "no budget governs this scope", spares hot-path DB hits. */
	public static String noneKey(BudgetScope scope, String ref) {
		return "budget:none:" + scope.dbValue() + ":" + ref;
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

	/**
	 * 9.1 admin CRUD: create or change a budget limit and make it enforce immediately.
	 * The Postgres row is the source of truth; the Redis counter is a mirror, so it is
	 * evicted (both the remaining counter and the "no budget" negative cache) and then
	 * rebuilt from limit - ledger spend. Eviction is required because {@link #rebuild}
	 * installs the counter with SETNX - a stale counter would otherwise survive the
	 * limit change. Returns the fresh remaining.
	 */
	public BigDecimal upsertLimit(BudgetScope scope, String ref, BigDecimal limitAmount) {
		Budget budget = budgets.findByScopeTypeAndScopeRef(scope.dbValue(), ref)
				.map(existing -> {
					existing.setLimitAmount(limitAmount);
					existing.setActive(true);
					return existing;
				})
				.orElseGet(() -> new Budget(scope.dbValue(), ref, limitAmount));
		budgets.save(budget);
		evictCounter(scope, ref);
		BigDecimal remaining = rebuild(scope, ref);
		log.info("budget upserted scope={} ref={} limit={} remaining={}",
				scope.dbValue(), ref, limitAmount.toPlainString(), remaining.toPlainString());
		return remaining;
	}

	/**
	 * 9.1 admin CRUD: deactivate a budget so it no longer governs. The counter and limit
	 * mirror are removed; the scope reverts to ungoverned (charges become no-ops).
	 */
	public void deactivate(BudgetScope scope, String ref) {
		budgets.findByScopeTypeAndScopeRefAndActiveTrue(scope.dbValue(), ref).ifPresent(budget -> {
			budget.setActive(false);
			budgets.save(budget);
		});
		redis.delete(counterKey(scope, ref));
		redis.delete(limitKey(scope, ref));
		redis.delete(noneKey(scope, ref));
		log.info("budget deactivated scope={} ref={}", scope.dbValue(), ref);
	}

	// drop the mirrored counter + negative cache so rebuild's SETNX installs a fresh value
	private void evictCounter(BudgetScope scope, String ref) {
		redis.delete(counterKey(scope, ref));
		redis.delete(noneKey(scope, ref));
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
		redis.opsForValue().set(limitKey(scope, ref), Long.toString(toNanos(budget.getLimitAmount())));
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

	/** Live remaining in nanodollars, or null when no counter exists (no rebuild attempted). */
	public Long remainingNanos(BudgetScope scope, String ref) {
		String value = redis.opsForValue().get(counterKey(scope, ref));
		return value == null ? null : Long.parseLong(value);
	}

	public static long toNanos(BigDecimal amount) {
		return amount.setScale(9, RoundingMode.HALF_UP).multiply(NANOS).longValueExact();
	}

	public static BigDecimal fromNanos(long nanos) {
		return BigDecimal.valueOf(nanos).divide(NANOS);
	}
}
