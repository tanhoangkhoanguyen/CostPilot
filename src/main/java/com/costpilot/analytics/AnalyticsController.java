package com.costpilot.analytics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.costpilot.analytics.dto.BudgetUtilization;
import com.costpilot.analytics.dto.DecisionCounts;
import com.costpilot.analytics.dto.ReconciliationResult;
import com.costpilot.analytics.dto.SavingsSummary;
import com.costpilot.analytics.dto.SpendBucket;
import com.costpilot.analytics.dto.TopSpender;
import com.costpilot.analytics.dto.TrendPoint;
import com.costpilot.security.AuthenticatedPrincipal;
import com.costpilot.security.CurrentPrincipal;

// 5.4 read API: spend analytics served from ClickHouse (OLAP), Postgres stays the ledger.
// All endpoints take an optional [from,to) window (default: last 30 days). Available only
// when ClickHouse is enabled.
// 6.1: per-team isolation - a non-admin key is confined to its own team_id, applied as a
// predicate INSIDE the ClickHouse dedup query (not a post-filter); a tenant-admin sees all.
@RestController
@RequestMapping("/api/analytics")
@ConditionalOnProperty(name = "costpilot.clickhouse.enabled", havingValue = "true")
public class AnalyticsController {

	// null when the caller is a tenant-admin (no team confinement); otherwise the caller's
	// own team, forced into every query.
	private static String teamScope() {
		AuthenticatedPrincipal principal = CurrentPrincipal.require();
		return principal.admin() ? null : principal.teamId();
	}

	private final AnalyticsQueryService analytics;

	public AnalyticsController(AnalyticsQueryService analytics) {
		this.analytics = analytics;
	}

	@GetMapping("/spend")
	public List<SpendBucket> spend(
			@RequestParam(defaultValue = "team") String groupBy,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.spendByDimension(groupBy, from(from), to(to), teamScope());
	}

	@GetMapping("/top-spenders")
	public List<TopSpender> topSpenders(
			@RequestParam(defaultValue = "team") String dimension,
			@RequestParam(defaultValue = "10") int limit,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.topSpenders(dimension, Math.min(Math.max(limit, 1), 100), from(from), to(to), teamScope());
	}

	@GetMapping("/decisions")
	public DecisionCounts decisions(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.decisionCounts(from(from), to(to), teamScope());
	}

	@GetMapping("/trends")
	public List<TrendPoint> trends(
			@RequestParam(defaultValue = "day") String interval,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.trends(interval, from(from), to(to), teamScope());
	}

	@GetMapping("/budget-utilization")
	public List<BudgetUtilization> budgetUtilization(
			@RequestParam(defaultValue = "team") String scope,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.budgetUtilization(scope, from(from), to(to), teamScope());
	}

	@GetMapping("/savings")
	public SavingsSummary savings(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.savings(from(from), to(to), teamScope());
	}

	@GetMapping("/reconcile")
	public ReconciliationResult reconcile(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.reconcile(from(from), to(to), teamScope());
	}

	private static Instant from(Instant from) {
		return from != null ? from : Instant.now().minus(30, ChronoUnit.DAYS);
	}

	private static Instant to(Instant to) {
		return to != null ? to : Instant.now();
	}
}
