package com.costpilot.cost;

/**
 * Deterministic, in-memory estimate of how many tokens a run of generated text
 * costs. Used ONLY for the mid-stream running cost that drives the 4.3 cutoff
 * (12.1) - the provider's authoritative {@code usage} always supersedes it for
 * the ledger.
 *
 * <p><b>Why not a flat chars/4:</b> that rule of thumb is English-centric. Real
 * char-per-token density varies by script - Vietnamese and other diacritic Latin
 * tokenize ~2x denser, CJK ~4x denser - so a flat divisor under-counts non-Latin
 * generations mid-flight and the cutoff fires late (wider overshoot on exactly
 * those inputs). This weights each code point by a per-script token density.
 * Still an estimate, but directionally correct across scripts.
 *
 * <p>Pure integer arithmetic in <b>millitokens</b> (tokens x 1000) so accrual
 * stays exact and atomic on the streaming path - no float drift, no tokenizer
 * dependency, no I/O. ASCII keeps the historical 4-chars-per-token result exactly.
 */
final class TokenLengthEstimator {

	private TokenLengthEstimator() {
	}

	// tokens-per-char x 1000, by script bucket
	static final int ASCII_MILLITOKENS = 250; // ~4 chars/token (English rule of thumb)
	static final int DIACRITIC_MILLITOKENS = 500; // ~2 chars/token (Vietnamese, accented Latin, other alphabets)
	static final int CJK_MILLITOKENS = 1000; // ~1 char/token (Han/Kana/Hangul)

	/** Weighted token estimate x 1000 for one text delta. */
	static long millitokens(CharSequence text) {
		long sum = 0;
		int i = 0;
		int n = text.length();
		while (i < n) {
			int cp = Character.codePointAt(text, i);
			i += Character.charCount(cp);
			sum += weight(cp);
		}
		return sum;
	}

	private static int weight(int cp) {
		// basic Latin, digits, punctuation, whitespace - the well-covered BPE core
		if (cp < 0x80) {
			return ASCII_MILLITOKENS;
		}
		if (isCjk(cp)) {
			return CJK_MILLITOKENS;
		}
		// any other non-ASCII code point (Vietnamese diacritics, accented European,
		// Greek/Cyrillic/Arabic, combining marks, ...) tokenizes denser than English
		return DIACRITIC_MILLITOKENS;
	}

	private static boolean isCjk(int cp) {
		Character.UnicodeScript script = Character.UnicodeScript.of(cp);
		return script == Character.UnicodeScript.HAN
				|| script == Character.UnicodeScript.HIRAGANA
				|| script == Character.UnicodeScript.KATAKANA
				|| script == Character.UnicodeScript.HANGUL;
	}
}
