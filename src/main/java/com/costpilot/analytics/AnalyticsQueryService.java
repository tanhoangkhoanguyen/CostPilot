package com.costpilot.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.costpilot.analytics.dto.BudgetUtilization;
import com.costpilot.analytics.dto.DecisionCounts;
import com.costpilot.analytics.dto.ReconciliationResult;
import com.costpilot.analytics.dto.SpendBucket;
import com.costpilot.analytics.dto.TopSpender;
import com.costpilot.analytics.dto.TrendPoint;
import com.costpilot.budget.BudgetService;
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.domain.UsageRecordRepository;

// 5.4: spend analytics served from ClickHouse. Every money query wraps a dedup subquery
// (collapse duplicate event_id via argMax before aggregating) so results are exact even
// when ReplacingMergeTree merges haven't run yet. Postgres stays the money truth - only
// budget limits and reconciliation touch it. Enabled with the ClickHouse datasource.
@Service
@ConditionalOnProperty(name = "costpilot.clickhouse.enabled", havingValue = "true")
public class AnalyticsQueryService {

	private static final long NANOS_PER_USD = 1_000_000_000L;

	private final JdbcTemplate clickhouse;
	private final ClickHouseProperties props;
	private final UsageRecordRepository usageRepository;
	private final BudgetRepository budgetRepository;

	public AnalyticsQueryService(@Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouse,
			ClickHouseProperties props, UsageRecordRepository usageRepository, BudgetRepository budgetRepository) {
		this.clickhouse = clickhouse;
		this.props = props;
		this.usageRepository = usageRepository;
		this.budgetRepository = budgetRepository;
	}

	// The only dimensions a caller may group by - whitelisted to a fixed column name so
	// the value is never concatenated from raw user input (SQL-injection safe).
	public enum Dimension {
		TEAM("team_id"), PROJECT("project_id"), MODEL("executed_model"), USER("user_id");

		private final String column;

		Dimension(String column) {
			this.column = column;
		}

		public static String columnFor(String name) {
			return valueOf(name.toUpperCase()).column;
		}
	}

	private String table() {
		return props.getUsageEventsTable();
	}

	// deduped rows for [from,to): one row per event_id, latest ingest wins.
	// The window bounds are bound as epoch millis via fromUnixTimestamp64Milli - matching
	// exactly how event_ts was written on ingest (no driver/session-timezone ambiguity).
	// 6.1: when teamScope != null the subquery also constrains team_id = ? so per-team
	// isolation is applied INSIDE the aggregation, not as a post-filter - a non-admin can
	// never aggregate over another team's rows.
	private String dedupSubquery(String extraCols, boolean teamScoped) {
		return "select event_id, argMax(cost_nanos, ingested_at) as cost_nanos, "
				+ "argMax(input_tokens, ingested_at) as input_tokens, "
				+ "argMax(output_tokens, ingested_at) as output_tokens" + extraCols
				+ " from " + table()
				+ " where event_ts >= fromUnixTimestamp64Milli(?) "
				+ "and event_ts < fromUnixTimestamp64Milli(?)"
				+ (teamScoped ? " and team_id = ?" : "") + " group by event_id";
	}

	// bind the window bounds, then the optional team filter, in the order the ? appear
	private static Object[] windowArgs(Instant from, Instant to, String teamScope) {
		return teamScope == null
				? new Object[] { ts(from), ts(to) }
				: new Object[] { ts(from), ts(to), teamScope };
	}

	public List<SpendBucket> spendByDimension(String dimension, Instant from, Instant to, String teamScope) {
		String col = Dimension.columnFor(dimension);
		String sql = "select k as key, sum(cost_nanos) as cost_nanos, count() as requests, "
				+ "sum(input_tokens) as in_tok, sum(output_tokens) as out_tok from ("
				+ dedupSubquery(", argMax(" + col + ", ingested_at) as k", teamScope != null)
				+ ") group by k order by cost_nanos desc";
		return clickhouse.query(sql, (rs, i) -> new SpendBucket(
				rs.getString("key"), usd(rs.getLong("cost_nanos")),
				rs.getLong("requests"), rs.getLong("in_tok"), rs.getLong("out_tok")),
				windowArgs(from, to, teamScope));
	}

	public List<TopSpender> topSpenders(String dimension, int limit, Instant from, Instant to, String teamScope) {
		String col = Dimension.columnFor(dimension);
		String sql = "select k as key, sum(cost_nanos) as cost_nanos, count() as requests from ("
				+ dedupSubquery(", argMax(" + col + ", ingested_at) as k", teamScope != null)
				+ ") group by k order by cost_nanos desc limit ?";
		Object[] base = windowArgs(from, to, teamScope);
		Object[] args = java.util.Arrays.copyOf(base, base.length + 1);
		args[base.length] = limit;
		return clickhouse.query(sql, (rs, i) -> new TopSpender(
				rs.getString("key"), usd(rs.getLong("cost_nanos")), rs.getLong("requests")),
				args);
	}

	public DecisionCounts decisionCounts(Instant from, Instant to, String teamScope) {
		// decision comes from the event; a cutoff is an allow/downgrade whose stream was
		// truncated, identified by finish_reason=budget_cutoff
		String sql = "select "
				+ "countIf(finish_reason = 'budget_cutoff') as cutoff, "
				+ "countIf(decision = 'allow' and finish_reason != 'budget_cutoff') as allow, "
				+ "countIf(decision = 'downgrade' and finish_reason != 'budget_cutoff') as downgrade, "
				+ "countIf(decision = 'route' and finish_reason != 'budget_cutoff') as route, "
				+ "countIf(decision = 'deny') as deny, "
				+ "countIf(decision = 'require_approval') as approval from ("
				+ dedupSubquery(", argMax(decision, ingested_at) as decision, "
						+ "argMax(finish_reason, ingested_at) as finish_reason", teamScope != null)
				+ ")";
		return clickhouse.queryForObject(sql, (rs, i) -> new DecisionCounts(
				rs.getLong("allow"), rs.getLong("downgrade"), rs.getLong("route"), rs.getLong("cutoff"),
				rs.getLong("deny"), rs.getLong("approval")), windowArgs(from, to, teamScope));
	}

	public List<TrendPoint> trends(String interval, Instant from, Instant to, String teamScope) {
		String unit = "hour".equalsIgnoreCase(interval) ? "1 hour" : "1 day";
		String sql = "select toStartOfInterval(ev_ts, interval " + unit + ") as bucket, "
				+ "sum(cost_nanos) as cost_nanos, count() as requests from ("
				+ dedupSubquery(", argMax(event_ts, ingested_at) as ev_ts", teamScope != null)
				+ ") group by bucket order by bucket";
		return clickhouse.query(sql, (rs, i) -> new TrendPoint(
				rs.getTimestamp("bucket").toInstant(), usd(rs.getLong("cost_nanos")), rs.getLong("requests")),
				windowArgs(from, to, teamScope));
	}

	// Budget limits from Postgres (money truth), spend from ClickHouse (reporting),
	// joined in Java by scope ref. scopeType maps to the grouping dimension.
	// 6.1: a non-admin (teamScope != null) may only see its own team's utilization - the
	// scope is forced to team and the result confined to its own budget row.
	public List<BudgetUtilization> budgetUtilization(String scopeType, Instant from, Instant to, String teamScope) {
		String effectiveScope = teamScope != null ? "team" : scopeType;
		String dimension = switch (effectiveScope.toLowerCase()) {
			case "team" -> "team";
			case "project" -> "project";
			default -> throw new IllegalArgumentException("unsupported budget scope: " + scopeType);
		};
		Map<String, Long> spendByRef = new java.util.HashMap<>();
		for (SpendBucket b : spendByDimension(dimension, from, to, teamScope)) {
			spendByRef.put(b.key(), new BigDecimal(b.costUsd()).movePointRight(9).longValueExact());
		}
		return budgetRepository.findAll().stream()
				.filter(Budget::isActive)
				.filter(b -> b.getScopeType().equalsIgnoreCase(effectiveScope))
				.filter(b -> teamScope == null || teamScope.equals(b.getScopeRef()))
				.map(b -> {
					long limitNanos = BudgetService.toNanos(b.getLimitAmount());
					long spentNanos = spendByRef.getOrDefault(b.getScopeRef(), 0L);
					Double util = limitNanos == 0 ? null : (double) spentNanos / limitNanos;
					return new BudgetUtilization(b.getScopeRef(), usd(limitNanos), usd(spentNanos), util);
				})
				.toList();
	}

	// 5.3/5.4 acceptance: ClickHouse totals reconcile with the Postgres ledger for a
	// fixed window. Compared as exact integer nanodollars - no float drift.
	// 6.1: a non-admin reconciles only its own team (both sides team-scoped); an admin
	// reconciles the whole window.
	public ReconciliationResult reconcile(Instant from, Instant to, String teamScope) {
		long pgRows;
		long pgNanos;
		if (teamScope == null) {
			pgRows = usageRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
			pgNanos = BudgetService.toNanos(usageRepository.totalCostBetween(from, to));
		} else {
			pgRows = usageRepository.countByTeamIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
					teamScope, from, to);
			pgNanos = BudgetService.toNanos(usageRepository.totalCostForTeamBetween(teamScope, from, to));
		}

		String sql = "select count() as n, sum(cost_nanos) as cost_nanos from ("
				+ "select event_id, argMax(cost_nanos, ingested_at) as cost_nanos from " + table()
				+ " where event_ts >= fromUnixTimestamp64Milli(?) "
				+ "and event_ts < fromUnixTimestamp64Milli(?)"
				+ (teamScope != null ? " and team_id = ?" : "") + " group by event_id)";
		Map<String, Object> ch = clickhouse.queryForMap(sql, windowArgs(from, to, teamScope));
		long chRows = ((Number) ch.get("n")).longValue();
		long chNanos = ch.get("cost_nanos") == null ? 0 : ((Number) ch.get("cost_nanos")).longValue();

		boolean reconciled = pgRows == chRows && pgNanos == chNanos;
		return new ReconciliationResult(pgRows, chRows, pgNanos, chNanos, reconciled);
	}

	// epoch millis - fromUnixTimestamp64Milli reads it into the UTC DateTime64 with no
	// timezone ambiguity, matching exactly how event_ts was written on ingest.
	private static long ts(Instant instant) {
		return instant.toEpochMilli();
	}

	private static String usd(long nanos) {
		return BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(NANOS_PER_USD), 9, RoundingMode.HALF_UP)
				.toPlainString();
	}
}
