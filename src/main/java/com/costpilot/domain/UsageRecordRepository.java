package com.costpilot.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

	Optional<UsageRecord> findByIdempotencyKey(String idempotencyKey);

	boolean existsByIdempotencyKey(String idempotencyKey);

	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u")
	BigDecimal totalCost();

	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u where u.tenantId = :ref")
	BigDecimal totalCostForTenant(@Param("ref") String ref);

	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u where u.teamId = :ref")
	BigDecimal totalCostForTeam(@Param("ref") String ref);

	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u where u.projectId = :ref")
	BigDecimal totalCostForProject(@Param("ref") String ref);

	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u where u.model = :ref")
	BigDecimal totalCostForModel(@Param("ref") String ref);

	// 5.4 reconciliation: ledger cost sum over a half-open window [from, to). Compared
	// against the ClickHouse total for the same window to prove the OLAP pipeline is exact.
	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u where u.createdAt >= :from and u.createdAt < :to")
	BigDecimal totalCostBetween(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

	long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.Instant from, java.time.Instant to);
}
