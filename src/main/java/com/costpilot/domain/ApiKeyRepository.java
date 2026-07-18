package com.costpilot.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

// 6.1: api-key auth. Lookup is by key_hash (unique, indexed) so authentication is an
// O(1) index hit on the hot path - the reason the hash is a deterministic HMAC and not
// a per-row-salted bcrypt (which could not be indexed). See security/ApiKeyHasher.
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

	Optional<ApiKey> findByKeyHash(String keyHash);
}
