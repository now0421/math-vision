package com.mathvision.util;

/**
 * Heuristic token estimator for input budget management.
 *
 * Uses a conservative character-based heuristic for provider-side token limits.
 * This intentionally overestimates mixed Chinese/JSON/tool-schema prompts,
 * because underestimation causes provider-side "prompt too long" failures.
 */
public final class TokenEstimator {

    private static final int TOKEN_UNIT_DIVISOR = 4;
    private static final int WHITESPACE_TOKEN_UNITS = 1;
    private static final int ASCII_TEXT_TOKEN_UNITS = 2;
    private static final int ASCII_SYMBOL_TOKEN_UNITS = 3;
    private static final int NON_ASCII_TOKEN_UNITS = 4;

    private TokenEstimator() {}

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int units = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            units += tokenUnits(codePoint);
            i += Character.charCount(codePoint);
        }
        return (units + TOKEN_UNIT_DIVISOR - 1) / TOKEN_UNIT_DIVISOR;
    }

    private static int tokenUnits(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return WHITESPACE_TOKEN_UNITS;
        }
        if (codePoint > 0x7F) {
            return NON_ASCII_TOKEN_UNITS;
        }
        return isAsciiText(codePoint)
                ? ASCII_TEXT_TOKEN_UNITS
                : ASCII_SYMBOL_TOKEN_UNITS;
    }

    private static boolean isAsciiText(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '_'
                || codePoint == '-';
    }
}
