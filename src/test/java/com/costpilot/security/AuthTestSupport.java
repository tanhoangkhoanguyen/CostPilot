package com.costpilot.security;

import org.springframework.http.HttpHeaders;

// 6.1: shared auth for integration tests. The seeded demo keys (V8) resolve under the
// dev pepper; the admin key may impersonate any team via X-Team-ID, which is why the
// existing governance ITs authenticate as admin and keep their arbitrary team headers.
public final class AuthTestSupport {

	// raw keys whose HMAC-SHA256 hashes are seeded in V8__api_key_auth.sql
	public static final String ADMIN_KEY = "cp_admin_root";
	public static final String TEAM_PLATFORM_KEY = "cp_demo_team_platform";
	public static final String TEAM_RESEARCH_KEY = "cp_demo_team_research";

	private AuthTestSupport() {
	}

	/** Authorization: Bearer header for the given raw key. */
	public static HttpHeaders bearer(String rawKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(rawKey);
		return headers;
	}

	/** Admin bearer - the default for ITs that impersonate arbitrary teams via headers. */
	public static HttpHeaders admin() {
		return bearer(ADMIN_KEY);
	}
}
