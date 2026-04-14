package com.mathvision.util;

/**
 * Heuristic token estimator for input budget management.
 *
 * Uses a simple character-based heuristic: ASCII non-whitespace characters
 * count as ~0.25 tokens, non-ASCII non-whitespace characters count as ~0.5 tokens.
 */
public final class TokenEstimator {

    private static final int TOKEN_UNIT_DIVISOR = 4;
    private static final int ASCII_TOKEN_UNITS = 1;
    private static final int NON_ASCII_TOKEN_UNITS = 2;

    private TokenEstimator() {}

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int units = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                units += codePoint <= 0x7F ? ASCII_TOKEN_UNITS : NON_ASCII_TOKEN_UNITS;
            }
            i += Character.charCount(codePoint);
        }
        return (units + TOKEN_UNIT_DIVISOR - 1) / TOKEN_UNIT_DIVISOR;
    }
}
