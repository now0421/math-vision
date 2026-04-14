package com.mathvision.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared low-level helpers for generated-code validation across targets.
 */
public final class CodeValidationSupport {

    private CodeValidationSupport() {}

    public static String normalizeForComparison(String generatedCode) {
        return generatedCode == null ? "" : generatedCode.trim().replace("\r\n", "\n");
    }

    public static String findFirstMatchEvidence(String generatedCode, Pattern pattern) {
        if (generatedCode == null || generatedCode.isBlank() || pattern == null) {
            return null;
        }

        String[] lines = generatedCode.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                String fragment = matcher.group();
                if (fragment == null || fragment.isBlank()) {
                    fragment = lines[i].trim();
                }
                fragment = fragment.replace("\t", " ").trim();
                if (fragment.length() > 120) {
                    fragment = fragment.substring(0, 120) + "...";
                }
                return "line " + (i + 1) + ": " + fragment;
            }
        }

        return null;
    }

    public static int countLines(String generatedCode) {
        if (generatedCode == null || generatedCode.isBlank()) {
            return 0;
        }
        return generatedCode.split("\\R").length;
    }
}
