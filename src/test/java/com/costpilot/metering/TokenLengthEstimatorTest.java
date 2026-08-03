package com.costpilot.metering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenLengthEstimatorTest {

	@Test
	void emptyTextIsZero() {
		assertThat(TokenLengthEstimator.millitokens("")).isZero();
	}

	@Test
	void asciiKeepsTheHistoricalFourCharsPerTokenWeight() {
		// 11 ASCII chars x 250 = 2750 millitokens -> ceil 3 tokens, exactly chars/4
		assertThat(TokenLengthEstimator.millitokens("hello world")).isEqualTo(11 * 250L);
	}

	@Test
	void cjkWeighsAboutOneTokenPerChar() {
		// 4 Han ideographs -> 4000 millitokens (a flat chars/4 would call this ~1 token)
		assertThat(TokenLengthEstimator.millitokens("你好世界")).isEqualTo(4 * 1000L);
	}

	@Test
	void diacriticLatinWeighsDenserThanAsciiButLighterThanCjk() {
		// "cafe" with an accented e: c,a,f ASCII (250 each) + accented e (500)
		long cafe = TokenLengthEstimator.millitokens("café");
		assertThat(cafe).isEqualTo(3 * 250L + 500L);
	}

	@Test
	void vietnameseDiacriticsCountDenserThanPlainAscii() {
		// same char count, different script density: "chao" (ASCII) vs "chao" with tone marks
		long plain = TokenLengthEstimator.millitokens("chao");
		long toned = TokenLengthEstimator.millitokens("chào"); // ch + a-grave + o
		assertThat(toned).isGreaterThan(plain);
	}

	@Test
	void densityOrderingHoldsPerScript() {
		long ascii = TokenLengthEstimator.millitokens("a");
		long diacritic = TokenLengthEstimator.millitokens("é"); // e-acute
		long cjk = TokenLengthEstimator.millitokens("世"); // Han
		assertThat(ascii).isLessThan(diacritic);
		assertThat(diacritic).isLessThan(cjk);
	}

	@Test
	void handlesAstralCodePointsAsOneUnit() {
		// a single emoji (surrogate pair) is one code point, weighed once as non-ASCII
		assertThat(TokenLengthEstimator.millitokens("😀")).isEqualTo(500L);
	}
}
