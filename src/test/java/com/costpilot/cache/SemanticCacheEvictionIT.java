package com.costpilot.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.cache.PromptCacheRepository.Hit;

import io.micrometer.core.instrument.MeterRegistry;

// 2.4 (#98) acceptance: entries past TTL are not served and are swept; the cache size stays
// bounded; the evictions counter reflects expiries. TTL is set to 1h and a row is backdated
// 2h so staleness is deterministic (no wall-clock sleeps).
@SpringBootTest(properties = {
		"costpilot.cache.enabled=true",
		"costpilot.cache.similarity-threshold=0.97",
		"costpilot.cache.ttl=PT1H",
		// long interval so the scheduled sweep never races the test; we invoke sweep() directly
		"costpilot.cache.eviction-interval-ms=3600000" })
@Import(TestcontainersConfiguration.class)
class SemanticCacheEvictionIT {

	@Autowired
	private PromptCacheRepository repository;

	@Autowired
	private PromptCacheEvictionSweeper sweeper;

	@Autowired
	private SemanticCacheService cache;

	@Autowired
	private Embedder embedder;

	@Autowired
	private MeterRegistry registry;

	@Autowired
	private DataSource dataSource;

	// store a row, then force its created_at to `age` ago so its TTL state is deterministic
	private void storeAged(String tenant, String team, String prompt, java.time.Duration age) {
		float[] embedding = embedder.embed(prompt);
		repository.store(tenant, team, prompt, embedding, "gpt-4o-mini", "{\"cached\":true}", 10, 5, 1_000_000L);
		Instant createdAt = Instant.now().minus(age);
		new JdbcTemplate(dataSource).update(
				"update prompt_cache set created_at = ? where tenant_id is not distinct from ? "
						+ "and team_id is not distinct from ? and prompt = ?",
				Timestamp.from(createdAt), tenant, team, prompt);
	}

	private long rowsFor(String team) {
		return new JdbcTemplate(dataSource).queryForObject(
				"select count(*) from prompt_cache where team_id = ?", Long.class, team);
	}

	@Test
	void aPastTtlEntryIsNeverServedEvenBeforeTheSweep() {
		String tenant = null;
		String team = "evict-stale-" + UUID.randomUUID();
		String prompt = "summarize the annual compliance report for the audit committee";
		storeAged(tenant, team, prompt, java.time.Duration.ofHours(2)); // older than the 1h TTL

		// the row physically exists, but lookup must exclude it (created_at < now - ttl)
		assertThat(rowsFor(team)).isEqualTo(1);
		Optional<Hit> hit = repository.nearest(tenant, team, embedder.embed(prompt), cache.staleBefore());
		assertThat(hit).isEmpty();
	}

	@Test
	void aFreshEntryIsStillServed() {
		String tenant = null;
		String team = "evict-fresh-" + UUID.randomUUID();
		String prompt = "explain the routing tier bar and how downgrade picks a cheaper model";
		storeAged(tenant, team, prompt, java.time.Duration.ofMinutes(5)); // well within the 1h TTL

		Optional<Hit> hit = repository.nearest(tenant, team, embedder.embed(prompt), cache.staleBefore());
		assertThat(hit).isPresent();
		assertThat(hit.get().similarity()).isGreaterThanOrEqualTo(0.97);
	}

	@Test
	void theSweepDeletesPastTtlRowsAndCountsThemButKeepsFreshOnes() {
		String team = "evict-sweep-" + UUID.randomUUID();
		storeAged(null, team, "stale prompt one about invoices and receivables aging", java.time.Duration.ofHours(3));
		storeAged(null, team, "stale prompt two about payroll and tax withholding tables", java.time.Duration.ofHours(5));
		storeAged(null, team, "fresh prompt about the mid-stream budget cutoff behavior", java.time.Duration.ofMinutes(1));
		assertThat(rowsFor(team)).isEqualTo(3);

		double before = evictionCount();
		sweeper.sweep();

		// the two past-TTL rows are gone, the fresh one survives (cache stays bounded)
		assertThat(rowsFor(team)).isEqualTo(1);
		// the evictions counter moved by exactly the number removed (hit-rate/expiry visibility)
		assertThat(evictionCount() - before).isEqualTo(2.0);
	}

	private double evictionCount() {
		var counter = registry.find("costpilot.cache.evictions").counter();
		return counter == null ? 0.0 : counter.count();
	}
}
