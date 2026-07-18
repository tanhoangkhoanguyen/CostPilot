package com.costpilot.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.costpilot.domain.ApiKey;
import com.costpilot.domain.ApiKeyRepository;
import com.costpilot.security.ApiKeyHasher;

import jakarta.validation.constraints.NotNull;

// 6.1: mint API keys. ROLE_ADMIN only (enforced in SecurityConfig on /admin/keys/**).
// The raw key is generated here, returned to the caller EXACTLY ONCE, and only its
// HMAC-SHA256 hash is persisted - the plaintext is never stored or recoverable.
@RestController
@RequestMapping("/admin/keys")
public class AdminKeyController {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ApiKeyRepository apiKeys;
	private final ApiKeyHasher hasher;

	public AdminKeyController(ApiKeyRepository apiKeys, ApiKeyHasher hasher) {
		this.apiKeys = apiKeys;
		this.hasher = hasher;
	}

	public record MintRequest(
			@NotNull UUID teamId,
			UUID projectId,
			String name,
			boolean admin) {
	}

	// key is present ONLY in this response - it is never stored in plaintext
	public record MintResponse(UUID id, String key, String name, boolean admin) {
	}

	@PostMapping
	public MintResponse mint(@RequestBody MintRequest request) {
		String rawKey = generateKey();
		ApiKey key = new ApiKey(request.teamId(), request.projectId(), hasher.hash(rawKey),
				request.name() != null ? request.name() : "minted", request.admin());
		ApiKey saved = apiKeys.save(key);
		return new MintResponse(saved.getId(), rawKey, saved.getName(), saved.isAdmin());
	}

	private static String generateKey() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return "cp_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
