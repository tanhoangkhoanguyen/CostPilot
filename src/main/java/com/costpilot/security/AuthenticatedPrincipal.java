package com.costpilot.security;

import java.util.UUID;

/**
 * 6.1: the resolved identity behind an authenticated request.
 *
 * Names (tenantId/teamId/projectId) are the string identities used throughout the ledger,
 * budget and policy path - resolved from the api_key's team_id/project_id UUIDs by joining
 * the tenant/team/project tables. teamUuid is kept for exact per-team isolation predicates.
 * admin=true is a tenant-admin key that may read across teams; otherwise the request is
 * force-scoped to its own team on the admin/analytics surfaces.
 */
public record AuthenticatedPrincipal(
		String tenantId,
		String teamId,
		String projectId,
		UUID teamUuid,
		boolean admin) {
}
