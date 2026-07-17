package com.costpilot.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// Admin query (5.1) is built with JPA Specifications (see AuditRecordSpecifications) so
// only the filters the caller actually supplied become SQL predicates - no bare null
// bind parameters, which PostgreSQL rejects with "could not determine data type".
public interface AuditRecordRepository
		extends JpaRepository<AuditRecord, UUID>, JpaSpecificationExecutor<AuditRecord> {
}
