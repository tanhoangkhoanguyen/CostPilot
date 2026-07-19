package com.costpilot.policy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.costpilot.cost.LedgerContext;
import com.costpilot.domain.PolicyRule;
import com.costpilot.domain.PolicyRuleRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runtime policy: which models this team/project may use (3.3). Rules live in
 * Postgres (change without redeploy); evaluation reads a Redis hot cache that is
 * explicitly invalidated on every rule change, so changes apply immediately.
 * A project rule overrides the team rule. No rule at all -> ALLOW (default-open
 * until the org writes policy). Redis down -> evaluate straight from the DB.
 */
@Service
public class PolicyService {

	private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
	static final Duration CACHE_TTL = Duration.ofSeconds(60);
	private static final String NONE = "__none__";

	private final PolicyRuleRepository rules;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;

	public PolicyService(PolicyRuleRepository rules, StringRedisTemplate redis, ObjectMapper objectMapper) {
		this.rules = rules;
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	// cache payload - a trimmed view of PolicyRule, JSON in Redis
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CachedRule(String id, String allowedModels, String fallbackAction, String downgradeTo,
			Long approvalThresholdNanos) {
	}

	public static String cacheKey(String scopeType, String scopeRef) {
		return "policy:rule:" + scopeType + ":" + scopeRef;
	}

	public PolicyDecision evaluate(LedgerContext context, String model) {
		PolicyDecision decision = doEvaluate(context, model);
		log.info("policy decision={} model={} executedModel={} rule={} reason=\"{}\"",
				decision.decision(), model, decision.executedModel(), decision.matchedRuleId(), decision.reason());
		return decision;
	}

	/** Quiet check used by candidate selection (4.1) - no decision log spam. */
	public boolean allows(LedgerContext context, String model) {
		return doEvaluate(context, model).decision() == PolicyDecision.Decision.ALLOW;
	}

	private PolicyDecision doEvaluate(LedgerContext context, String model) {
		// project override wins over the team rule
		CachedRule rule = null;
		String matchedScope = null;
		if (context.projectId() != null && !context.projectId().isBlank()) {
			rule = load("project", context.projectId());
			matchedScope = "project=" + context.projectId();
		}
		if (rule == null && context.teamId() != null && !context.teamId().isBlank()) {
			rule = load("team", context.teamId());
			matchedScope = "team=" + context.teamId();
		}
		return decide(rule, matchedScope, model);
	}

	private PolicyDecision decide(CachedRule rule, String matchedScope, String model) {
		if (rule == null) {
			return PolicyDecision.allowDefault(model);
		}
		java.util.UUID ruleId = java.util.UUID.fromString(rule.id());
		if (matches(rule.allowedModels(), model)) {
			// carry the rule's cost gate (8.1): the controller escalates to approval only
			// after it has the pre-flight estimate. Null threshold = plain allow.
			return new PolicyDecision(PolicyDecision.Decision.ALLOW, model, ruleId,
					"model allowed by " + matchedScope + " rule", rule.approvalThresholdNanos());
		}
		return switch (rule.fallbackAction()) {
			case "downgrade" -> new PolicyDecision(PolicyDecision.Decision.DOWNGRADE, rule.downgradeTo(), ruleId,
					"model not in allowed set for " + matchedScope + "; downgraded to " + rule.downgradeTo());
			case "require_approval" -> new PolicyDecision(PolicyDecision.Decision.REQUIRE_APPROVAL, model, ruleId,
					"model not in allowed set for " + matchedScope + "; approval required");
			default -> new PolicyDecision(PolicyDecision.Decision.DENY, model, ruleId,
					"model not in allowed set for " + matchedScope);
		};
	}

	static boolean matches(String allowedModels, String model) {
		return Arrays.stream(allowedModels.split(","))
				.map(String::trim)
				.filter(p -> !p.isEmpty())
				.anyMatch(pattern -> pattern.equals("*")
						|| pattern.equalsIgnoreCase(model)
						|| (pattern.endsWith("*")
								&& model.toLowerCase().startsWith(
										pattern.substring(0, pattern.length() - 1).toLowerCase())));
	}

	/** Hot path: Redis cache first (60s TTL + explicit invalidation), DB on miss. */
	private CachedRule load(String scopeType, String scopeRef) {
		String key = cacheKey(scopeType, scopeRef);
		try {
			String cached = redis.opsForValue().get(key);
			if (NONE.equals(cached)) {
				return null;
			}
			if (cached != null) {
				return objectMapper.readValue(cached, CachedRule.class);
			}
		} catch (DataAccessException e) {
			log.warn("policy cache read failed, evaluating from DB: {}", e.getMessage());
			return fromDb(scopeType, scopeRef).orElse(null);
		} catch (Exception e) {
			log.warn("policy cache entry unreadable, refreshing: {}", e.getMessage());
		}
		Optional<CachedRule> rule = fromDb(scopeType, scopeRef);
		try {
			redis.opsForValue().set(key, rule.map(this::toJson).orElse(NONE), CACHE_TTL);
		} catch (DataAccessException e) {
			log.warn("policy cache write failed: {}", e.getMessage());
		}
		return rule.orElse(null);
	}

	private Optional<CachedRule> fromDb(String scopeType, String scopeRef) {
		return rules.findByScopeTypeAndScopeRefAndActiveTrue(scopeType, scopeRef)
				.map(r -> new CachedRule(r.getId().toString(), r.getAllowedModels(),
						r.getFallbackAction(), r.getDowngradeTo(), r.getApprovalThresholdNanos()));
	}

	/** Create or update a rule; the cache is invalidated so it applies immediately. */
	@Transactional
	public PolicyRule upsertRule(String scopeType, String scopeRef, String allowedModels,
			String fallbackAction, String downgradeTo) {
		return upsertRule(scopeType, scopeRef, allowedModels, fallbackAction, downgradeTo, null);
	}

	/** As above, with the 8.1 approval cost threshold (nanodollars; null = no cost gate). */
	@Transactional
	public PolicyRule upsertRule(String scopeType, String scopeRef, String allowedModels,
			String fallbackAction, String downgradeTo, Long approvalThresholdNanos) {
		PolicyRule rule = rules.findByScopeTypeAndScopeRefAndActiveTrue(scopeType, scopeRef)
				.map(existing -> {
					existing.update(allowedModels, fallbackAction, downgradeTo, approvalThresholdNanos);
					return existing;
				})
				.orElseGet(() -> rules.save(new PolicyRule(scopeType, scopeRef, allowedModels, fallbackAction,
						downgradeTo, approvalThresholdNanos)));
		rules.flush();
		evict(scopeType, scopeRef);
		log.info("policy rule upserted scope={}:{} allowed=\"{}\" fallback={} threshold={} rule={}",
				scopeType, scopeRef, allowedModels, fallbackAction, approvalThresholdNanos, rule.getId());
		return rule;
	}

	/** 9.1 admin CRUD: deactivate a rule so the scope reverts to default-open; invalidated. */
	@Transactional
	public void deactivateRule(String scopeType, String scopeRef) {
		rules.findByScopeTypeAndScopeRefAndActiveTrue(scopeType, scopeRef).ifPresent(rule -> {
			rule.deactivate();
			rules.flush();
		});
		evict(scopeType, scopeRef);
		log.info("policy rule deactivated scope={}:{}", scopeType, scopeRef);
	}

	public void evict(String scopeType, String scopeRef) {
		try {
			redis.delete(cacheKey(scopeType, scopeRef));
		} catch (DataAccessException e) {
			log.warn("policy cache evict failed (entry expires in {}s anyway): {}",
					CACHE_TTL.toSeconds(), e.getMessage());
		}
	}

	private String toJson(CachedRule rule) {
		try {
			return objectMapper.writeValueAsString(rule);
		} catch (Exception e) {
			throw new IllegalStateException("unserializable policy rule", e);
		}
	}
}
