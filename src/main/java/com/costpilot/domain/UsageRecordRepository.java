package com.costpilot.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

	Optional<UsageRecord> findByIdempotencyKey(String idempotencyKey);

	boolean existsByIdempotencyKey(String idempotencyKey);

	@Query("select coalesce(sum(u.cost), 0) from UsageRecord u")
	BigDecimal totalCost();
}
