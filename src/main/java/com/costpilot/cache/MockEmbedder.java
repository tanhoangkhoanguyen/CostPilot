package com.costpilot.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic $0 embedder (default). A bag-of-words hashing embedding: each lowercased
 * word is hashed into the vector space and accumulated, then the vector is L2-normalized.
 * This gives the property the cache needs - prompts that share most words land close
 * together (high cosine), unrelated prompts land far apart - without any network call, so
 * dev and tests are free and deterministic. A real embedding provider replaces this via
 * costpilot.cache.embedder=real (mirrors the mock-vs-real upstream switch).
 */
@Component
@ConditionalOnProperty(name = "costpilot.cache.embedder", havingValue = "mock", matchIfMissing = true)
public class MockEmbedder implements Embedder {

	static final int DIMENSION = 384;

	@Override
	public int dimension() {
		return DIMENSION;
	}

	@Override
	public float[] embed(String text) {
		float[] vector = new float[DIMENSION];
		if (text != null) {
			for (String word : text.toLowerCase().split("\\W+")) {
				if (word.isEmpty()) {
					continue;
				}
				// stable per-word hash -> one dimension gets +1; a second hash gives the
				// sign, so distinct words spread across the space and partially cancel.
				int h = word.hashCode();
				int index = Math.floorMod(h, DIMENSION);
				vector[index] += (h & 1) == 0 ? 1f : -1f;
			}
		}
		return normalize(vector);
	}

	private static float[] normalize(float[] v) {
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		norm = Math.sqrt(norm);
		if (norm == 0) {
			// empty/blank prompt: a fixed unit vector so it is self-consistent
			v[0] = 1f;
			return v;
		}
		for (int i = 0; i < v.length; i++) {
			v[i] /= (float) norm;
		}
		return v;
	}
}
