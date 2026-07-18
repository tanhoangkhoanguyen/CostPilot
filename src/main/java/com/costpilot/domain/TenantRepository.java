package com.costpilot.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

// 6.1: resolve the team's tenant_id (UUID) to the tenant name - populates the previously
// null LedgerContext.tenantId once a request is authenticated.
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
