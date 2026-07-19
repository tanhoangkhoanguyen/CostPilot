package com.costpilot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingApprovalRepository extends JpaRepository<PendingApproval, UUID> {

	// list-pending for the approvals API (9.2), newest first
	List<PendingApproval> findByStateOrderByCreatedAtDesc(PendingApproval.State state);

	// team-scoped list so a non-admin only sees its own team's pending requests
	List<PendingApproval> findByTeamIdAndStateOrderByCreatedAtDesc(String teamId, PendingApproval.State state);

	// isolation: fetch a specific pending request only within the caller's team
	Optional<PendingApproval> findByIdAndTeamId(UUID id, String teamId);

	// the expiry sweep: pending rows past their TTL
	List<PendingApproval> findByStateAndExpiresAtBefore(PendingApproval.State state, Instant cutoff);
}
