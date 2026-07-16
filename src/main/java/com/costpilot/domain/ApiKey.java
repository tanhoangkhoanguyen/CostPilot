package com.costpilot.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_key")
public class ApiKey {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "team_id", nullable = false)
	private UUID teamId;

	@Column(name = "project_id")
	private UUID projectId;

	// only the hash is ever stored, never the raw key
	@Column(name = "key_hash", nullable = false, unique = true, columnDefinition = "text")
	private String keyHash;

	@Column(nullable = false, columnDefinition = "text")
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected ApiKey() {
	}

	public ApiKey(UUID teamId, UUID projectId, String keyHash, String name) {
		this.teamId = teamId;
		this.projectId = projectId;
		this.keyHash = keyHash;
		this.name = name;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTeamId() {
		return teamId;
	}

	public UUID getProjectId() {
		return projectId;
	}

	public String getKeyHash() {
		return keyHash;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}
}
