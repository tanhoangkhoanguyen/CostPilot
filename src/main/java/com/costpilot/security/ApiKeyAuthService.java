package com.costpilot.security;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.costpilot.domain.ApiKey;
import com.costpilot.domain.ApiKeyRepository;
import com.costpilot.domain.Project;
import com.costpilot.domain.ProjectRepository;
import com.costpilot.domain.Team;
import com.costpilot.domain.TeamRepository;
import com.costpilot.domain.Tenant;
import com.costpilot.domain.TenantRepository;

/**
 * 6.1: turns a presented raw API key into an {@link AuthenticatedPrincipal}.
 *
 * hash(key) → api_key lookup (reject revoked) → join team/project/tenant to resolve the
 * name identities the rest of the pipeline speaks. Resolved principals are cached briefly
 * keyed by the hash, so the common case is a single ConcurrentHashMap read and the DB is
 * only touched on a cache miss - keeping authentication off the &lt;5ms hot-path budget.
 */
@Service
public class ApiKeyAuthService {

	private static final Duration CACHE_TTL = Duration.ofSeconds(60);

	private final ApiKeyHasher hasher;
	private final ApiKeyRepository apiKeys;
	private final TeamRepository teams;
	private final ProjectRepository projects;
	private final TenantRepository tenants;

	private record CachedPrincipal(AuthenticatedPrincipal principal, long cachedAt) {
	}

	private final ConcurrentHashMap<String, CachedPrincipal> cache = new ConcurrentHashMap<>();

	public ApiKeyAuthService(ApiKeyHasher hasher, ApiKeyRepository apiKeys, TeamRepository teams,
			ProjectRepository projects, TenantRepository tenants) {
		this.hasher = hasher;
		this.apiKeys = apiKeys;
		this.teams = teams;
		this.projects = projects;
		this.tenants = tenants;
	}

	/**
	 * @throws InvalidApiKeyException if the key is blank, unknown, or revoked.
	 */
	public AuthenticatedPrincipal authenticate(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw new InvalidApiKeyException("missing api key");
		}
		String keyHash = hasher.hash(rawKey);

		CachedPrincipal cached = cache.get(keyHash);
		if (cached != null && System.currentTimeMillis() - cached.cachedAt() < CACHE_TTL.toMillis()) {
			return cached.principal();
		}

		ApiKey key = apiKeys.findByKeyHash(keyHash)
				.orElseThrow(() -> new InvalidApiKeyException("unknown api key"));
		if (key.isRevoked()) {
			// don't cache a revoked key - a re-issue under the same hash is impossible
			// (hash is unique) so there's nothing to serve stale, and this keeps the
			// negative result from lingering
			throw new InvalidApiKeyException("revoked api key");
		}

		AuthenticatedPrincipal principal = resolve(key);
		cache.put(keyHash, new CachedPrincipal(principal, System.currentTimeMillis()));
		return principal;
	}

	private AuthenticatedPrincipal resolve(ApiKey key) {
		Team team = teams.findById(key.getTeamId())
				.orElseThrow(() -> new InvalidApiKeyException("api key references a missing team"));
		Tenant tenant = tenants.findById(team.getTenantId())
				.orElseThrow(() -> new InvalidApiKeyException("team references a missing tenant"));
		String projectName = Optional.ofNullable(key.getProjectId())
				.flatMap(projects::findById)
				.map(Project::getName)
				.orElse(null);
		return new AuthenticatedPrincipal(tenant.getName(), team.getName(), projectName,
				team.getId(), key.isAdmin());
	}
}
