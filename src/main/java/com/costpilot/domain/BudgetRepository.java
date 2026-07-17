package com.costpilot.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

	Optional<Budget> findByScopeTypeAndScopeRefAndActiveTrue(String scopeType, String scopeRef);
}
