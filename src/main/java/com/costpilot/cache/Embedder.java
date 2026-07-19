package com.costpilot.cache;

/**
 * Turns a prompt into a fixed-dimension embedding for similarity lookup (10.1). The
 * default implementation is a deterministic local mock ($0, no network) so dev and tests
 * cost nothing; a real embedding provider drops in via config, mirroring the mock-vs-real
 * upstream pattern. Implementations must return a unit-length vector of {@link #dimension}.
 */
public interface Embedder {

	int dimension();

	float[] embed(String text);
}
