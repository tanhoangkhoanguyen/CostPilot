package com.costpilot.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.CanonicalChatRequest;
import com.costpilot.cost.LedgerContext;
import com.costpilot.domain.PendingApproval;
import com.costpilot.domain.PendingApprovalRepository;
import com.costpilot.domain.UsageRecordRepository;

// 8.1/8.2 acceptance: a parked request is persisted (survives restart), an approved
// request replays end-to-end into the ledger, and a rejected/expired request never
// reaches a provider (no ledger row). Drives the services directly - the REST surface
// is Stage 9.2.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PendingApprovalStateMachineIT {

	@Autowired
	private PendingApprovalService parkService;

	@Autowired
	private ApprovalDecisionService decisions;

	@Autowired
	private PendingApprovalRepository pendingRepository;

	@Autowired
	private UsageRecordRepository usageRepository;

	@BeforeEach
	void clean() {
		pendingRepository.deleteAll();
		usageRepository.deleteAll();
	}

	private CanonicalChatRequest request() {
		return new CanonicalChatRequest("gpt-4o-mini",
				List.of(new CanonicalChatRequest.Message("user", "hello approval")), 64, false);
	}

	private LedgerContext ledger(String key) {
		return new LedgerContext("acme", "approval-team", "proj", "user", "test", key);
	}

	private PendingApproval park(String key) {
		return parkService.park(request(), ledger(key), "gpt-4o-mini", null,
				"estimated cost over approval threshold", null);
	}

	@Test
	void parkedRequestIsPersistedWithFullContextAndSurvivesReload() {
		PendingApproval parked = park("park-" + UUID.randomUUID());

		// re-fetch from the DB (not the in-memory instance) - state that survives a restart
		PendingApproval reloaded = pendingRepository.findById(parked.getId()).orElseThrow();
		assertThat(reloaded.getState()).isEqualTo(PendingApproval.State.pending);
		assertThat(reloaded.getRequestedModel()).isEqualTo("gpt-4o-mini");
		assertThat(reloaded.getTeamId()).isEqualTo("approval-team");
		assertThat(reloaded.getRequestPayload()).contains("hello approval");
		assertThat(reloaded.getEstimateNanos()).isNotNull();
		assertThat(reloaded.getExpiresAt()).isAfter(Instant.now());
		// parked, not forwarded: nothing in the ledger yet
		assertThat(usageRepository.count()).isZero();
	}

	@Test
	void approvedRequestReplaysIntoTheLedgerAndStoresTheResponse() {
		PendingApproval parked = park("approve-" + UUID.randomUUID());

		ApprovalDecisionService.Outcome outcome = decisions.approve(parked, "admin@acme");

		assertThat(outcome.response()).isNotNull();
		assertThat(outcome.response().choices().get(0).message().content()).isNotBlank();
		PendingApproval decided = pendingRepository.findById(parked.getId()).orElseThrow();
		assertThat(decided.getState()).isEqualTo(PendingApproval.State.approved);
		assertThat(decided.getDecidedBy()).isEqualTo("admin@acme");
		assertThat(decided.getStoredResponse()).contains("chat.completion");
		// approved request forwarded + ledgered exactly like a live one
		assertThat(usageRepository.count()).isEqualTo(1);
		assertThat(usageRepository.findAll().get(0).getModel()).isEqualTo("gpt-4o-mini");
	}

	@Test
	void rejectedRequestNeverForwardsAndIsRecordedWithReason() {
		PendingApproval parked = park("reject-" + UUID.randomUUID());

		PendingApproval rejected = decisions.reject(parked, "admin@acme", "too expensive");

		assertThat(rejected.getState()).isEqualTo(PendingApproval.State.rejected);
		assertThat(rejected.getDecisionReason()).isEqualTo("too expensive");
		assertThat(usageRepository.count()).isZero();
	}

	@Test
	void expiredRequestNeverForwardsAndIsRecorded() {
		PendingApproval parked = park("expire-" + UUID.randomUUID());

		PendingApproval expired = decisions.expire(parked);

		assertThat(expired.getState()).isEqualTo(PendingApproval.State.expired);
		assertThat(expired.getDecisionReason()).isEqualTo("TTL expired");
		assertThat(usageRepository.count()).isZero();
	}

	@Test
	void aTerminalRequestCannotBeDecidedAgain() {
		PendingApproval parked = park("double-" + UUID.randomUUID());
		decisions.reject(parked, "admin@acme", "no");

		PendingApproval reloaded = pendingRepository.findById(parked.getId()).orElseThrow();
		assertThatThrownBy(() -> decisions.approve(reloaded, "admin@acme"))
				.isInstanceOf(ApprovalDecisionService.NotPendingException.class);
		assertThat(usageRepository.count()).isZero();
	}
}
