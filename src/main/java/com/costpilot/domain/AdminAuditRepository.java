package com.costpilot.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditRepository extends JpaRepository<AdminAudit, UUID> {

	List<AdminAudit> findByActorOrderByCreatedAtDesc(String actor);

	List<AdminAudit> findByTargetTypeAndTargetRefOrderByCreatedAtDesc(String targetType, String targetRef);
}
