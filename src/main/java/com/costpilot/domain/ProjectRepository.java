package com.costpilot.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

// 6.1: resolve the api_key's project_id (nullable UUID) to the project name.
public interface ProjectRepository extends JpaRepository<Project, UUID> {
}
