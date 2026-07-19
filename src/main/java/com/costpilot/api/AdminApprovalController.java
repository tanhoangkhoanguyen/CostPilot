package com.costpilot.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.costpilot.admin.AdminAuditService;
import com.costpilot.approval.ApprovalDecisionService;
import com.costpilot.domain.PendingApproval;
import com.costpilot.domain.PendingApprovalRepository;
import com.costpilot.security.AuthenticatedPrincipal;
import com.costpilot.security.CurrentPrincipal;

/**
 * 9.2: act on REQUIRE_APPROVAL requests programmatically. Lists pending requests and
 * drives the Stage 8 state machine (approve/reject). ROLE_ADMIN only. Approving replays
 * the parked request through the shared governed executor and returns its response;
 * rejecting records the reason. Both are recorded in the admin audit.
 */
@RestController
@RequestMapping("/admin/approvals")
public class AdminApprovalController {

	private final PendingApprovalRepository repository;
	private final ApprovalDecisionService decisions;
	private final AdminAuditService adminAudit;

	public AdminApprovalController(PendingApprovalRepository repository, ApprovalDecisionService decisions,
			AdminAuditService adminAudit) {
		this.repository = repository;
		this.decisions = decisions;
		this.adminAudit = adminAudit;
	}

	public record PendingView(UUID id, String state, String team, String requestedModel, Long estimateNanos,
			String reason, Instant createdAt, Instant expiresAt) {

		static PendingView of(PendingApproval p) {
			return new PendingView(p.getId(), p.getState().name(), p.getTeamId(), p.getRequestedModel(),
					p.getEstimateNanos(), p.getReason(), p.getCreatedAt(), p.getExpiresAt());
		}
	}

	public record RejectRequest(String reason) {
	}

	@GetMapping
	public List<PendingView> listPending() {
		return repository.findByStateOrderByCreatedAtDesc(PendingApproval.State.pending).stream()
				.map(PendingView::of).toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable UUID id) {
		return repository.findById(id)
				.<ResponseEntity<?>>map(p -> ResponseEntity.ok(PendingView.of(p)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/{id}/approve")
	public ResponseEntity<?> approve(@PathVariable UUID id) {
		AuthenticatedPrincipal actor = CurrentPrincipal.require();
		PendingApproval pending = repository.findById(id).orElse(null);
		if (pending == null) {
			return ResponseEntity.notFound().build();
		}
		try {
			ApprovalDecisionService.Outcome outcome = decisions.approve(pending, actor.tenantId());
			adminAudit.record(actor.tenantId(), "approval.approve", "approval", id.toString(), "pending", "approved");
			return ResponseEntity.ok(outcome.response());
		} catch (ApprovalDecisionService.NotPendingException alreadyDecided) {
			return ResponseEntity.status(409).body(errorBody(alreadyDecided.getMessage()));
		}
	}

	@PostMapping("/{id}/reject")
	public ResponseEntity<?> reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body) {
		AuthenticatedPrincipal actor = CurrentPrincipal.require();
		PendingApproval pending = repository.findById(id).orElse(null);
		if (pending == null) {
			return ResponseEntity.notFound().build();
		}
		String reason = body != null ? body.reason() : null;
		try {
			PendingApproval rejected = decisions.reject(pending, actor.tenantId(), reason);
			adminAudit.record(actor.tenantId(), "approval.reject", "approval", id.toString(), "pending", "rejected");
			return ResponseEntity.ok(PendingView.of(rejected));
		} catch (ApprovalDecisionService.NotPendingException alreadyDecided) {
			return ResponseEntity.status(409).body(errorBody(alreadyDecided.getMessage()));
		}
	}

	private static java.util.Map<String, String> errorBody(String message) {
		return java.util.Map.of("error", "conflict", "message", message);
	}
}
