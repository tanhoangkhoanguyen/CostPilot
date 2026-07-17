package com.costpilot.api;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.costpilot.api.dto.AuditRecordDto;
import com.costpilot.domain.AuditRecordRepository;
import com.costpilot.domain.AuditRecordSpecifications;

// 5.1 admin query surface: filter the audit trail by team / project / decision / time.
// NOTE: unsecured for now - Spring Security + per-team isolation lands in Stage 6.1.
// Do not expose this publicly until then.
@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {

	private static final int MAX_PAGE_SIZE = 200;
	private static final int DEFAULT_PAGE_SIZE = 50;

	private final AuditRecordRepository repository;

	public AdminAuditController(AuditRecordRepository repository) {
		this.repository = repository;
	}

	@GetMapping
	public Page<AuditRecordDto> search(
			@RequestParam(required = false) String teamId,
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String decision,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

		Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		return repository.findAll(
				AuditRecordSpecifications.filter(blankToNull(teamId), blankToNull(projectId),
						blankToNull(decision), from, to),
				pageable)
				.map(AuditRecordDto::from);
	}

	// The fully-explained single decision (5.1: "why this decision").
	@GetMapping("/{id}")
	public ResponseEntity<AuditRecordDto> byId(@PathVariable java.util.UUID id) {
		return repository.findById(id)
				.map(AuditRecordDto::from)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private static int clampSize(int size) {
		if (size < 1) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(size, MAX_PAGE_SIZE);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
