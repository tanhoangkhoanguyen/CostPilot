package com.costpilot.api;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.costpilot.admin.AdminAuditService;
import com.costpilot.domain.PolicyRule;
import com.costpilot.domain.PolicyRuleRepository;
import com.costpilot.policy.PolicyService;
import com.costpilot.security.AuthenticatedPrincipal;
import com.costpilot.security.CurrentPrincipal;

import jakarta.validation.constraints.NotNull;

/**
 * 9.1: manage policy rules (allowed models, fallback action, approval cost threshold)
 * without touching the DB. ROLE_ADMIN only. PolicyService.upsertRule already invalidates
 * the Redis hot cache, so changes enforce on the next request; every action is audited.
 */
@RestController
@RequestMapping("/admin/policies")
public class AdminPolicyController {

	private final PolicyService policyService;
	private final PolicyRuleRepository rules;
	private final AdminAuditService adminAudit;

	public AdminPolicyController(PolicyService policyService, PolicyRuleRepository rules,
			AdminAuditService adminAudit) {
		this.policyService = policyService;
		this.rules = rules;
		this.adminAudit = adminAudit;
	}

	public record PolicyView(String scopeType, String scopeRef, String allowedModels, String fallbackAction,
			String downgradeTo, Long approvalThresholdNanos, boolean active) {

		static PolicyView of(PolicyRule r) {
			return new PolicyView(r.getScopeType(), r.getScopeRef(), r.getAllowedModels(), r.getFallbackAction(),
					r.getDowngradeTo(), r.getApprovalThresholdNanos(), r.isActive());
		}
	}

	public record UpsertRequest(
			@NotNull String scopeType,
			@NotNull String scopeRef,
			@NotNull String allowedModels,
			@NotNull String fallbackAction,
			String downgradeTo,
			Long approvalThresholdNanos) {
	}

	@GetMapping
	public List<PolicyView> list() {
		return rules.findAll().stream().map(PolicyView::of).toList();
	}

	@PutMapping
	public PolicyView upsert(@RequestBody UpsertRequest request) {
		AuthenticatedPrincipal actor = CurrentPrincipal.require();
		String old = rules.findByScopeTypeAndScopeRefAndActiveTrue(request.scopeType(), request.scopeRef())
				.map(r -> r.getAllowedModels() + "/" + r.getFallbackAction())
				.orElse(null);
		PolicyRule saved = policyService.upsertRule(request.scopeType(), request.scopeRef(),
				request.allowedModels(), request.fallbackAction(), request.downgradeTo(),
				request.approvalThresholdNanos());
		adminAudit.record(actor.tenantId(), "policy.upsert", request.scopeType(), request.scopeRef(),
				old, request.allowedModels() + "/" + request.fallbackAction());
		return PolicyView.of(saved);
	}

	@DeleteMapping
	public void deactivate(@RequestParam String scopeType, @RequestParam String scopeRef) {
		AuthenticatedPrincipal actor = CurrentPrincipal.require();
		String old = rules.findByScopeTypeAndScopeRefAndActiveTrue(scopeType, scopeRef)
				.map(r -> r.getAllowedModels() + "/" + r.getFallbackAction())
				.orElse(null);
		policyService.deactivateRule(scopeType, scopeRef);
		adminAudit.record(actor.tenantId(), "policy.deactivate", scopeType, scopeRef, old, null);
	}
}
