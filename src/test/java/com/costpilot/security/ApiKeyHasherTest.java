package com.costpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

	@Test
	void hashIsDeterministicForTheSameKeyAndPepper() {
		ApiKeyHasher hasher = new ApiKeyHasher("pepper-1");
		assertThat(hasher.hash("cp_demo_abc")).isEqualTo(hasher.hash("cp_demo_abc"));
	}

	@Test
	void differentKeysProduceDifferentHashes() {
		ApiKeyHasher hasher = new ApiKeyHasher("pepper-1");
		assertThat(hasher.hash("cp_demo_abc")).isNotEqualTo(hasher.hash("cp_demo_xyz"));
	}

	@Test
	void theSameKeyUnderADifferentPepperHashesDifferently() {
		// the pepper is what defends the at-rest hash: without it a dumped table can't be
		// reversed or precomputed
		String key = "cp_demo_abc";
		assertThat(new ApiKeyHasher("pepper-1").hash(key))
				.isNotEqualTo(new ApiKeyHasher("pepper-2").hash(key));
	}

	@Test
	void hashIsHexSha256Width() {
		// HMAC-SHA256 -> 32 bytes -> 64 hex chars, and only hex chars
		String hash = new ApiKeyHasher("pepper-1").hash("cp_demo_abc");
		assertThat(hash).hasSize(64).matches("[0-9a-f]+");
	}
}
