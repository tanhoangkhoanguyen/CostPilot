package com.costpilot.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {

	Optional<PolicyRule> findByScopeTypeAndScopeRefAndActiveTrue(String scopeType, String scopeRef);
}
