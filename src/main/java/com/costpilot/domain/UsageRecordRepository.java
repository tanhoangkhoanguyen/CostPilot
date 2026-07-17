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
}
