package com.costpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.costpilot.domain.ApiKey;
import com.costpilot.domain.ApiKeyRepository;
import com.costpilot.domain.Project;
import com.costpilot.domain.ProjectRepository;
import com.costpilot.domain.Team;
import com.costpilot.domain.TeamRepository;
import com.costpilot.domain.Tenant;
import com.costpilot.domain.TenantRepository;

import org.mockito.Mockito;

class ApiKeyAuthServiceTest {

	private final ApiKeyHasher hasher = new ApiKeyHasher("test-pepper");
	private ApiKeyRepository apiKeys;
	private TeamRepository teams;
	private ProjectRepository projects;
	private TenantRepository tenants;
	private ApiKeyAuthService service;

	private final UUID tenantId = UUID.randomUUID();
	private final UUID teamId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		apiKeys = Mockito.mock(ApiKeyRepository.class);
		teams = Mockito.mock(TeamRepository.class);
		projects = Mockito.mock(ProjectRepository.class);
		tenants = Mockito.mock(TenantRepository.class);
		service = new ApiKeyAuthService(hasher, apiKeys, teams, projects, tenants);

		when(teams.findById(teamId)).thenReturn(Optional.of(withId(new Team(tenantId, "platform"), teamId)));
		when(tenants.findById(tenantId)).thenReturn(Optional.of(new Tenant("acme")));
		when(projects.findById(projectId)).thenReturn(Optional.of(new Project(teamId, "chatbot")));
	}

	@Test
	void resolvesNamesFromTheKeyTeamAndProject() {
		stubKey("cp_team_key", new ApiKey(teamId, projectId, hasher.hash("cp_team_key"), "team", false));

		AuthenticatedPrincipal principal = service.authenticate("cp_team_key");

		assertThat(principal.tenantId()).isEqualTo("acme");
		assertThat(principal.teamId()).isEqualTo("platform");
		assertThat(principal.projectId()).isEqualTo("chatbot");
		assertThat(principal.teamUuid()).isEqualTo(teamId);
		assertThat(principal.admin()).isFalse();
	}

	@Test
	void adminFlagIsCarriedThrough() {
		stubKey("cp_admin_key", new ApiKey(teamId, null, hasher.hash("cp_admin_key"), "admin", true));

		AuthenticatedPrincipal principal = service.authenticate("cp_admin_key");

		assertThat(principal.admin()).isTrue();
		assertThat(principal.projectId()).isNull();
	}

	@Test
	void blankKeyIsRejected() {
		assertThatThrownBy(() -> service.authenticate("  "))
				.isInstanceOf(InvalidApiKeyException.class);
	}

	@Test
	void unknownKeyIsRejected() {
		when(apiKeys.findByKeyHash(Mockito.anyString())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.authenticate("cp_nope"))
				.isInstanceOf(InvalidApiKeyException.class);
	}

	@Test
	void revokedKeyIsRejected() {
		ApiKey revoked = new ApiKey(teamId, null, hasher.hash("cp_revoked"), "old", false);
		setField(revoked, "revokedAt", Instant.now());
		stubKey("cp_revoked", revoked);

		assertThatThrownBy(() -> service.authenticate("cp_revoked"))
				.isInstanceOf(InvalidApiKeyException.class);
	}

	private void stubKey(String rawKey, ApiKey key) {
		when(apiKeys.findByKeyHash(hasher.hash(rawKey))).thenReturn(Optional.of(key));
	}

	// entities have generated ids; set the id reflectively so the team lookup matches
	private static Team withId(Team team, UUID id) {
		setField(team, "id", id);
		return team;
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
