package com.costpilot.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

// Builds the admin audit-query predicate from only the filters that were supplied.
// A null filter contributes no predicate at all - so PostgreSQL never sees an untyped
// null bind parameter (the "could not determine data type" failure).
public final class AuditRecordSpecifications {

	private AuditRecordSpecifications() {
	}

	public static Specification<AuditRecord> filter(String teamId, String projectId, String decision,
			Instant from, Instant to) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (teamId != null) {
				predicates.add(cb.equal(root.get("teamId"), teamId));
			}
			if (projectId != null) {
				predicates.add(cb.equal(root.get("projectId"), projectId));
			}
			if (decision != null) {
				predicates.add(cb.equal(root.get("decision"), decision));
			}
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.get("createdAt"), to));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}
}
