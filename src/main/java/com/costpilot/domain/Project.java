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
@Table(name = "project")
public class Project {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "team_id", nullable = false)
	private UUID teamId;

	@Column(nullable = false, columnDefinition = "text")
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected Project() {
	}

	public Project(UUID teamId, String name) {
		this.teamId = teamId;
		this.name = name;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTeamId() {
		return teamId;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
