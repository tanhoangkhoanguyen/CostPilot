package com.costpilot.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 6.1: hashes API keys at rest.
 *
 * Uses a deterministic keyed hash - HMAC-SHA256 with a server-side pepper - rather than
 * bcrypt/argon2. This is a deliberate, documented choice:
 *
 *  - API keys are looked up on EVERY request. The hash must be deterministic so key_hash
 *    stays UNIQUE and indexable → authentication is a single O(1) index hit. A per-row
 *    salted KDF (bcrypt/argon2) cannot be indexed: it would force scanning every row and
 *    verifying one by one - O(n) per request, blowing the &lt;5ms hot-path budget.
 *  - Bcrypt/argon2 exist to slow down brute force against LOW-entropy human passwords.
 *    API keys here are high-entropy random tokens (128+ bits); brute force is already
 *    infeasible, so a slow KDF buys nothing.
 *  - The pepper (a server-side secret, not stored in the DB) is what defends the at-rest
 *    hash: an attacker who dumps the api_key table still cannot reverse or precompute
 *    hashes without it. This satisfies "keys stored hashed, never plaintext".
 */
@Component
public class ApiKeyHasher {

	private static final String HMAC_ALGO = "HmacSHA256";

	private final byte[] pepper;

	public ApiKeyHasher(@Value("${costpilot.security.api-key-pepper}") String pepper) {
		this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
	}

	/** Deterministic hex HMAC-SHA256 of the raw key under the server pepper. */
	public String hash(String rawKey) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(new SecretKeySpec(pepper, HMAC_ALGO));
			byte[] out = mac.doFinal(rawKey.getBytes(StandardCharsets.UTF_8));
			return toHex(out);
		} catch (java.security.GeneralSecurityException e) {
			// HmacSHA256 is guaranteed present on every JVM; a failure here is unrecoverable
			throw new IllegalStateException("api-key hashing unavailable", e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
