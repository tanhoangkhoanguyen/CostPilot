package com.costpilot.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelPriceRepository extends JpaRepository<ModelPrice, UUID> {

	Optional<ModelPrice> findByProviderAndModel(String provider, String model);
}
