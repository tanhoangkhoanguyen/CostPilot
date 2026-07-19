package com.costpilot.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.costpilot.admin.AdminAuditService;
import com.costpilot.budget.BudgetScope;
import com.costpilot.budget.BudgetService;
import com.costpilot.domain.Budget;
import com.costpilot.domain.BudgetRepository;
import com.costpilot.security.AuthenticatedPrincipal;
import com.costpilot.security.CurrentPrincipal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 9.1: manage budgets (tenant/team/project/model) without touching the DB by hand.
 * ROLE_ADMIN only (enforced in SecurityConfig on /admin/**). Changes take effect at
 * runtime - a budget upsert rebuilds the live Redis counter - and every action is
 * recorded in the admin audit.
 */
@RestController
@RequestMapping("/admin/budgets")
public class AdminBudgetController {

	private final BudgetService budgetService;
	private final BudgetRepository budgets;
	private final AdminAuditService adminAudit;

	public AdminBudgetController(BudgetService budgetService, BudgetRepository budgets,
			AdminAuditService adminAudit) {
		this.budgetService = budgetService;
		this.budgets = budgets;
		this.adminAudit = adminAudit;
	}

	public record BudgetView(String scope, String ref, BigDecimal limit, BigDecimal remaining, boolean active) {
	}

	public record UpsertRequest(
			@NotNull String scope,
			@NotNull String ref,
			@NotNull @Positive BigDecimal limit) {
	}

	@GetMapping
	public List<BudgetView> list() {
		return budgets.findAll().stream()
				.map(b -> new BudgetView(b.getScopeType(), b.getScopeRef(), b.getLimitAmount(),
						b.isActive() ? budgetService.remaining(
								BudgetScope.fromDbValue(b.getScopeType()), b.getScopeRef()) : null,
						b.isActive()))
				.toList();
	}

	@PutMapping
	public BudgetView upsert(@RequestBody UpsertRequest request) {
		AuthenticatedPrincipal actor = CurrentPrincipal.require();
		BudgetScope scope = BudgetScope.fromDbValue(request.scope());
		String old = budgets.findByScopeTypeAndScopeRef(scope.dbValue(), request.ref())
				.map(b -> b.getLimitAmount().toPlainString() + (b.isActive() ? "" : " (inactive)"))
				.orElse(null);
		BigDecimal remaining = budgetService.upsertLimit(scope, request.ref(), request.limit());
		adminAudit.record(actor.tenantId(), "budget.upsert", scope.dbValue(), request.ref(),
				old, request.limit().toPlainString());
		return new BudgetView(scope.dbValue(), request.ref(), request.limit(), remaining, true);
	}

	@DeleteMapping
	public void deactivate(@RequestParam String scope, @RequestParam String ref) {
		AuthenticatedPrincipal actor = CurrentPrincipal.require();
		BudgetScope parsed = BudgetScope.fromDbValue(scope);
		String old = budgets.findByScopeTypeAndScopeRefAndActiveTrue(parsed.dbValue(), ref)
				.map(b -> b.getLimitAmount().toPlainString())
				.orElse(null);
		budgetService.deactivate(parsed, ref);
		adminAudit.record(actor.tenantId(), "budget.deactivate", parsed.dbValue(), ref, old, null);
	}
}
