package com.costpilot.approval;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.costpilot.domain.PendingApproval;
import com.costpilot.domain.PendingApprovalRepository;

/**
 * 8.2 TTL: pending approvals past their expires_at are auto-rejected as expired so a
 * request the org never acted on cannot linger indefinitely (documented default 24h,
 * configurable via costpilot.approval.ttl). The sweep runs on a fixed delay; each row
 * transitions through the same guarded state machine as a manual decision.
 */
@Component
public class ApprovalExpirySweeper {

	private static final Logger log = LoggerFactory.getLogger(ApprovalExpirySweeper.class);

	private final PendingApprovalRepository repository;
	private final ApprovalDecisionService decisions;

	public ApprovalExpirySweeper(PendingApprovalRepository repository, ApprovalDecisionService decisions) {
		this.repository = repository;
		this.decisions = decisions;
	}

	@Scheduled(fixedDelayString = "${costpilot.approval.sweep-interval-ms:60000}")
	public void sweep() {
		List<PendingApproval> expired = repository.findByStateAndExpiresAtBefore(
				PendingApproval.State.pending, Instant.now());
		if (expired.isEmpty()) {
			return;
		}
		log.info("approval expiry sweep found {} past-TTL pending request(s)", expired.size());
		for (PendingApproval pending : expired) {
			try {
				decisions.expire(pending);
			} catch (ApprovalDecisionService.NotPendingException raced) {
				// a concurrent manual decision won the race - nothing to do
				log.debug("approval {} decided before expiry sweep", pending.getId());
			}
		}
	}
}
