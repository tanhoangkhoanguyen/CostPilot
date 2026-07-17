package com.costpilot.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelPriceRepository extends JpaRepository<ModelPrice, UUID> {

	@Query("""
			select p from ModelPrice p
			where p.provider = :provider and p.model = :model
			  and p.effectiveFrom <= :at
			  and (p.effectiveTo is null or p.effectiveTo > :at)
			""")
	Optional<ModelPrice> findActiveAt(@Param("provider") String provider, @Param("model") String model,
			@Param("at") Instant at);

	@Query("select p from ModelPrice p where p.effectiveTo is null")
	java.util.List<ModelPrice> findAllLive();
}
