package com.costpilot.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

// 6.1: resolve the api_key's team_id (UUID) to the team name used as the string identity
// throughout the ledger/budget/policy path.
public interface TeamRepository extends JpaRepository<Team, UUID> {
}
