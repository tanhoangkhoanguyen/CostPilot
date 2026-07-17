package com.costpilot.budget;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.Cost;
import com.costpilot.cost.CostEstimator;
import com.costpilot.cost.LedgerContext;
import com.costpilot.cost.PriceLookupService;
import com.costpilot.cost.PriceNotFoundException;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.domain.ModelPrice;
import com.costpilot.provider.ProviderRegistry;

/**
 * The hot-path pre-request check (3.2). Reserves the conservative max cost of
 * the request against every governed scope atomically (Lua), so a concurrent
 * flood cannot overspend a cap. Reservations are released after the request
 * settles - the actual charge lands via the ledger path (3.1).
 *
 * Hard limit: reservation would push remaining below zero -> BLOCK (402).
 * Soft limit: remaining after reservation at or under 20% of the cap -> serve + warn.
 * FAIL-OPEN: if Redis or the budget lookup is unavailable, ALLOW and log loudly.
 * Cost control must never take down production traffic - this is the single
 * deliberate reliability stance of this project, documented as intentional.
 */
@Component
public class BudgetGuard {

	private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);
	static final Duration NONE_CACHE_TTL = Duration.ofSeconds(60);

	// status codes: 0 = no counter (resolve + retry), 1 = allow, 2 = warn (soft), 3 = block (hard)
	@SuppressWarnings("rawtypes")
	private static final DefaultRedisScript<List> RESERVE = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 0 then return {0, 0} end
			local reserve = tonumber(ARGV[1])
			local rem = redis.call('DECRBY', KEYS[1], reserve)
			if rem < 0 then
				redis.call('INCRBY', KEYS[1], reserve)
				return {3, rem + reserve}
			end
			local lim = tonumber(redis.call('GET', KEYS[2]) or '0')
			if lim > 0 and rem * 5 <= lim then return {2, rem} end
			return {1, rem}
			""", List.class);

	public record Reservation(BudgetScope scope, String ref, long nanos) {
	}

	public record GuardResult(List<Reservation> reservations, String warning, boolean failOpen) {
		public static GuardResult allowFailOpen() {
			return new GuardResult(List.of(), null, true);
		}
	}

	private final StringRedisTemplate redis;
	private final BudgetService budgetService;
	private final BudgetRepository budgets;
	private final CostEstimator estimator;
	private final PriceLookupService priceLookup;
	private final ProviderRegistry registry;

	public BudgetGuard(StringRedisTemplate redis, BudgetService budgetService, BudgetRepository budgets,
			CostEstimator estimator, PriceLookupService priceLookup, ProviderRegistry registry) {
		this.redis = redis;
		this.budgetService = budgetService;
		this.budgets = budgets;
		this.estimator = estimator;
		this.priceLookup = priceLookup;
		this.registry = registry;
	}

	/**
	 * Reserve the request's estimated max cost against every governed scope.
	 * Throws BudgetExceededException on a hard-limit hit (all partial
	 * reservations are rolled back first).
	 */
	public GuardResult reserve(CanonicalChatRequest request, LedgerContext context) {
		try {
			long reserveNanos = BudgetService.toNanos(estimate(request));
			List<Reservation> held = new ArrayList<>();
			String warning = null;
			for (BudgetScope scope : BudgetScope.values()) {
				String ref = refFor(scope, request, context);
				if (ref == null || ref.isBlank()) {
					continue;
				}
				Long status = tryReserve(scope, ref, reserveNanos, held);
				if (status != null && status == 2) {
					warning = scope.dbValue() + "=" + ref + " budget below 20% remaining";
				}
			}
			return new GuardResult(held, warning, false);
		} catch (BudgetExceededException e) {
			throw e;
		} catch (DataAccessException | IllegalStateException e) {
			// FAIL-OPEN by design: the guard being down must not block traffic
			log.warn("budget guard fail-open: {}", e.getMessage());
			return GuardResult.allowFailOpen();
		}
	}

	/** Give back a request's reservations once the actual charge has settled. */
	public void release(GuardResult result) {
		for (Reservation reservation : result.reservations()) {
			try {
				redis.opsForValue().increment(
						BudgetService.counterKey(reservation.scope(), reservation.ref()), reservation.nanos());
			} catch (DataAccessException e) {
				log.warn("budget guard release fail-open scope={} ref={}: {}",
						reservation.scope().dbValue(), reservation.ref(), e.getMessage());
			}
		}
	}

	/** Returns the soft/hard status for the scope, or null if the scope is ungoverned. */
	private Long tryReserve(BudgetScope scope, String ref, long reserveNanos, List<Reservation> held) {
		Long status = evalReserve(scope, ref, reserveNanos);
		if (status == 0) {
			if (!resolveMissingCounter(scope, ref)) {
				return null; // no budget governs this scope
			}
			status = evalReserve(scope, ref, reserveNanos);
			if (status == 0) {
				return null;
			}
		}
		if (status == 3) {
			release(new GuardResult(held, null, false));
			throw new BudgetExceededException(scope, ref,
					BudgetService.fromNanos(remainingNanos(scope, ref)));
		}
		held.add(new Reservation(scope, ref, reserveNanos));
		return status;
	}

	@SuppressWarnings("unchecked")
	private Long evalReserve(BudgetScope scope, String ref, long reserveNanos) {
		List<Long> result = redis.execute(RESERVE,
				List.of(BudgetService.counterKey(scope, ref), BudgetService.limitKey(scope, ref)),
				Long.toString(reserveNanos));
		if (result == null || result.isEmpty()) {
			throw new IllegalStateException("budget reserve script returned nothing");
		}
		return result.get(0);
	}

	/** Counter missing: consult the negative cache, then Postgres; rebuild when governed. */
	private boolean resolveMissingCounter(BudgetScope scope, String ref) {
		if (redis.hasKey(BudgetService.noneKey(scope, ref))) {
			return false;
		}
		if (budgets.findByScopeTypeAndScopeRefAndActiveTrue(scope.dbValue(), ref).isEmpty()) {
			redis.opsForValue().set(BudgetService.noneKey(scope, ref), "1", NONE_CACHE_TTL);
			return false;
		}
		budgetService.rebuild(scope, ref);
		return true;
	}

	private long remainingNanos(BudgetScope scope, String ref) {
		String value = redis.opsForValue().get(BudgetService.counterKey(scope, ref));
		return value == null ? 0 : Long.parseLong(value);
	}

	// hot-path price cache: a DB price lookup per request blows the <5ms budget.
	// reservations tolerate a briefly stale rate; the ledger still charges with
	// the exact effective-dated price (2.3)
	private static final long PRICE_CACHE_TTL_MS = 30_000;

	private record CachedPrice(ModelPrice price, long cachedAt) {
	}

	private final java.util.concurrent.ConcurrentHashMap<String, CachedPrice> priceCache =
			new java.util.concurrent.ConcurrentHashMap<>();

	private BigDecimal estimate(CanonicalChatRequest request) {
		long now = System.currentTimeMillis();
		CachedPrice cached = priceCache.get(request.model());
		if (cached == null || now - cached.cachedAt() > PRICE_CACHE_TTL_MS) {
			cached = new CachedPrice(lookupPrice(request.model()), now);
			priceCache.put(request.model(), cached);
		}
		if (cached.price() == null) {
			// unpriced model: nothing to reserve, but existing counters still gate
			return BigDecimal.ZERO;
		}
		return estimator.estimateMax(request, cached.price()).total();
	}

	private ModelPrice lookupPrice(String model) {
		try {
			return priceLookup.priceAt(registry.forModel(model).providerId(), model, Instant.now());
		} catch (PriceNotFoundException e) {
			return null;
		}
	}

	private String refFor(BudgetScope scope, CanonicalChatRequest request, LedgerContext context) {
		return switch (scope) {
			case TENANT -> context.tenantId();
			case TEAM -> context.teamId();
			case PROJECT -> context.projectId();
			case MODEL -> request.model();
		};
	}
}
