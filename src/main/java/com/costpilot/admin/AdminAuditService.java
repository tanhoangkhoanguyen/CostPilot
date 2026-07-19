package com.costpilot.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.costpilot.domain.AdminAudit;
import com.costpilot.domain.AdminAuditRepository;

/**
 * 9.1: records every admin control-plane action (budget/policy CRUD, approval decisions)
 * so "who changed what, when" is queryable. Append-only; separate from the per-request
 * decision audit.
 */
@Service
public class AdminAuditService {

	private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

	private final AdminAuditRepository repository;

	public AdminAuditService(AdminAuditRepository repository) {
		this.repository = repository;
	}

	public AdminAudit record(String actor, String action, String targetType, String targetRef,
			String oldValue, String newValue) {
		AdminAudit saved = repository.save(new AdminAudit(actor, action, targetType, targetRef, oldValue, newValue));
		log.info("admin action actor={} action={} target={}:{} old={} new={}",
				actor, action, targetType, targetRef, oldValue, newValue);
		return saved;
	}
}
