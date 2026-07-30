package com.costpilot.cache;

import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Postgres ledger for semantic-cache hit savings (issue #93 / 1.3). Each cache hit
 * inserts one row; analytics sums {@code savings_nanos} over a window. Kept separate
 * from {@code usage_record.savings_nanos} (routing/downgrade only) so the two channels
 * cannot double-count.
 */
@Repository
public class CacheHitLogRepository {

	private final JdbcTemplate jdbc;

	public CacheHitLogRepository(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	public void record(String tenantId, String teamId, long savingsNanos) {
		jdbc.update("""
				insert into cache_hit_log (tenant_id, team_id, savings_nanos)
				values (?, ?, ?)
				""",
				tenantId, teamId, savingsNanos);
	}

	public long totalSavingsNanosBetween(Instant from, Instant to) {
		Long n = jdbc.queryForObject("""
				select coalesce(sum(savings_nanos), 0) from cache_hit_log
				where created_at >= ? and created_at < ?
				""", Long.class, ts(from), ts(to));
		return n == null ? 0L : n;
	}

	public long totalSavingsNanosForTeamBetween(String team, Instant from, Instant to) {
		Long n = jdbc.queryForObject("""
				select coalesce(sum(savings_nanos), 0) from cache_hit_log
				where team_id = ? and created_at >= ? and created_at < ?
				""", Long.class, team, ts(from), ts(to));
		return n == null ? 0L : n;
	}

	// JdbcTemplate + PG timestamptz: bind Timestamp, not Instant (Instant triggers bad SQL grammar)
	private static Timestamp ts(Instant instant) {
		return Timestamp.from(instant);
	}
}
