package com.costpilot.cache;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * pgvector-backed store for the semantic cache (10.1). Uses native SQL for the vector
 * column (the `vector` type is not a standard JDBC/Hibernate type) and enforces
 * tenant/team isolation directly in the lookup query, so a lookup can only ever match a
 * row with the same tenant and team.
 */
@Repository
public class PromptCacheRepository {

	private final JdbcTemplate jdbc;

	// build our own JdbcTemplate from the primary Postgres DataSource so this is
	// unambiguous even when the ClickHouse JdbcTemplate bean is also present.
	public PromptCacheRepository(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** A nearest-neighbor hit: the cached response plus its cosine similarity to the query. */
	public record Hit(String model, String response, int inputTokens, int outputTokens,
			long costNanos, double similarity) {
	}

	public void store(String tenantId, String teamId, String prompt, float[] embedding, String model,
			String response, int inputTokens, int outputTokens, long costNanos) {
		jdbc.update("""
				insert into prompt_cache
				  (tenant_id, team_id, prompt, embedding, model, response, input_tokens, output_tokens, cost_nanos)
				values (?, ?, ?, ?::vector, ?, ?, ?, ?, ?)
				""",
				tenantId, teamId, prompt, toVectorLiteral(embedding), model, response,
				inputTokens, outputTokens, costNanos);
	}

	/**
	 * Nearest neighbor within the same tenant/team, as (response, similarity). Cosine
	 * distance operator `&lt;=&gt;` returns 1 - cosine similarity; similarity = 1 - distance.
	 * Returns empty when the cache holds nothing for this scope.
	 */
	public Optional<Hit> nearest(String tenantId, String teamId, float[] embedding) {
		List<Hit> hits = jdbc.query("""
				select model, response, input_tokens, output_tokens, cost_nanos,
				       1 - (embedding <=> ?::vector) as similarity
				from prompt_cache
				where tenant_id is not distinct from ?
				  and team_id  is not distinct from ?
				order by embedding <=> ?::vector
				limit 1
				""",
				(rs, i) -> new Hit(rs.getString("model"), rs.getString("response"),
						rs.getInt("input_tokens"), rs.getInt("output_tokens"),
						rs.getLong("cost_nanos"), rs.getDouble("similarity")),
				toVectorLiteral(embedding), tenantId, teamId, toVectorLiteral(embedding));
		return hits.stream().findFirst();
	}

	static String toVectorLiteral(float[] embedding) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(embedding[i]);
		}
		return sb.append(']').toString();
	}
}
