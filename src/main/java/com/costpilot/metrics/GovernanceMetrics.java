package com.costpilot.metrics;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 6.2: the governance signals an operator runs the gateway from - budget rejections,
 * downgrades, mid-stream cutoffs, soft-limit warnings, token volume, and the two latencies
 * that matter (the &lt;5ms budget guard and upstream forwarding). Exposed via Micrometer at
 * /actuator/prometheus.
 *
 * Tag cardinality is deliberately bounded: reason/decision/direction are small fixed sets
 * and provider is the handful of adapters - safe as Prometheus labels. Team/project/model
 * are NOT tags here (unbounded) - that breakdown lives in the ClickHouse analytics path.
 */
@Component
public class GovernanceMetrics {

	private final MeterRegistry registry;

	private final Counter budgetRejections;
	private final Counter softLimitWarnings;
	private final Counter cutoffs;
	private final Timer budgetGuard;

	public GovernanceMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.budgetRejections = Counter.builder("costpilot.budget.rejections")
				.description("requests blocked because no policy-allowed model fit the remaining budget (402)")
				.register(registry);
		this.softLimitWarnings = Counter.builder("costpilot.budget.soft_limit_warnings")
				.description("requests served with a budget-warning header (soft limit hit)")
				.register(registry);
		this.cutoffs = Counter.builder("costpilot.stream.cutoffs")
				.description("streamed responses cut off mid-flight to stay within budget (the headline claim)")
				.register(registry);
		this.budgetGuard = Timer.builder("costpilot.budget.guard")
				.description("hot-path budget guard decision latency")
				.publishPercentiles(0.5, 0.95, 0.99)
				.register(registry);
	}

	public void budgetRejection() {
		budgetRejections.increment();
	}

	public void softLimitWarning() {
		softLimitWarnings.increment();
	}

	public void cutoff() {
		cutoffs.increment();
	}

	/** decision = deny | require_approval */
	public void policyRejection(String decision) {
		registry.counter("costpilot.policy.rejections", "decision", decision).increment();
	}

	/** reason = policy | budget */
	public void downgrade(String reason) {
		registry.counter("costpilot.downgrades", "reason", reason).increment();
	}

	/** 7.2: a request was cost-routed to a cheaper model that met its declared tier bar. */
	public void routed() {
		registry.counter("costpilot.routing.routed").increment();
	}

	public void recordTokens(long inputTokens, long outputTokens) {
		registry.counter("costpilot.tokens", "direction", "input").increment(inputTokens);
		registry.counter("costpilot.tokens", "direction", "output").increment(outputTokens);
	}

	public void recordGuardLatency(long nanos) {
		budgetGuard.record(nanos, TimeUnit.NANOSECONDS);
	}

	/** upstream forwarding latency, tagged by provider (bounded set of adapters). */
	public void recordUpstreamLatency(String provider, long nanos) {
		Timer.builder("costpilot.upstream")
				.description("upstream provider call latency")
				.tag("provider", provider)
				.publishPercentiles(0.5, 0.95, 0.99)
				.register(registry)
				.record(nanos, TimeUnit.NANOSECONDS);
	}
}
