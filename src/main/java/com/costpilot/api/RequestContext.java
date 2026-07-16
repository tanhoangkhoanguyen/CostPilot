package com.costpilot.api;

// Governance identity for a request, from X-Team-ID / X-Project-ID headers.
// Later stages resolve these against real tenants; for now they just travel with the request.
public record RequestContext(String teamId, String projectId) {

	public static RequestContext of(String teamId, String projectId) {
		return new RequestContext(teamId, projectId);
	}
}
