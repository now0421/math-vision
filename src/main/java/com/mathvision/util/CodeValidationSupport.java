package com.mathvision.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared low-level helpers for generated-code validation across targets.
 */
public final class CodeValidationSupport {

    private CodeValidationSupport() {}

    public static String normalizeForComparison(String generatedCode) {
        return generatedCode == null ? "" : generatedCode.replace("\r\n", "\n");
    }

    public static boolean hasCodeChanged(String sourceCode, String revisedCode) {
        return !normalizeForComparison(sourceCode).equals(normalizeForComparison(revisedCode));
    }

    public static List<String> findAllMatchEvidences(String generatedCode, Pattern pattern) {
        List<String> evidences = new ArrayList<>();
        if (generatedCode == null || generatedCode.isBlank() || pattern == null) {
            return evidences;
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
                evidences.add("line " + (i + 1) + ": " + fragment);
            }
        }

        return evidences;
    }

    public static int countLines(String generatedCode) {
        if (generatedCode == null || generatedCode.isBlank()) {
            return 0;
        }
        return generatedCode.split("\\R").length;
    }
}
