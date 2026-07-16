package com.costpilot.core.model;

// One provider-neutral streaming event. Exactly one "kind" applies per chunk;
// usage may ride along on the final events of providers that report it in-stream.
public record CanonicalStreamChunk(
		String roleDelta,
		String contentDelta,
		String finishReason,
		Usage usage,
		boolean done) {

	public static CanonicalStreamChunk role(String role) {
		return new CanonicalStreamChunk(role, null, null, null, false);
	}

	public static CanonicalStreamChunk content(String delta) {
		return new CanonicalStreamChunk(null, delta, null, null, false);
	}

	public static CanonicalStreamChunk finish(String reason) {
		return new CanonicalStreamChunk(null, null, reason, null, false);
	}

	public static CanonicalStreamChunk usageOnly(Usage usage) {
		return new CanonicalStreamChunk(null, null, null, usage, false);
	}

	public static CanonicalStreamChunk endOfStream() {
		return new CanonicalStreamChunk(null, null, null, null, true);
	}
}
