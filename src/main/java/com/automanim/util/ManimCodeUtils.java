package com.automanim.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for Manim code post-processing, validation, and normalization.
 */
public final class ManimCodeUtils {

    private ManimCodeUtils() {}

    public static final String EXPECTED_SCENE_NAME = "MainScene";

    private static final Pattern MAIN_SCENE_CLASS = Pattern.compile(
            "class\\s+MainScene\\s*\\(.*?Scene.*?\\)");

    private static final Pattern ANY_SCENE_CLASS = Pattern.compile(
            "class\\s+[^\\s(]+\\s*\\((.*?Scene.*?)\\)");

    private static final Pattern SCENE_CLASS = Pattern.compile(
            "class\\s+(\\w+)\\s*\\(.*?Scene.*?\\)");

    private static final Pattern NON_ASCII_IDENTIFIER = Pattern.compile(
            "(?:class|def)\\s+[^\\x00-\\x7F]+|self\\.[^\\x00-\\x7F]+|\\b[^\\x00-\\x7F_][^\\s(=:,]*");

    private static final Pattern RULE1_VIOLATION = Pattern.compile(
            "self\\.\\w+\\s*=\\s*(?:MathTex|Text|VGroup|Circle|Square|Line|Dot|Arrow|Axes|NumberPlane)");

    private static final Pattern RULE3_VIOLATION = Pattern.compile(
            "\\w+\\[\\d+\\]\\[\\d+:\\d+\\]");

    public static String extractCode(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String extracted = JsonUtils.extractCodeBlock(response);
        if (extracted != null && !extracted.isBlank()) {
            return extracted;
        }
        return response.trim();
    }

    public static String enforceMainSceneName(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        return ANY_SCENE_CLASS.matcher(code)
                .replaceFirst("class MainScene($1)");
    }

    public static String expectedSceneName() {
        return EXPECTED_SCENE_NAME;
    }

    public static String extractSceneName(String code, String fallback) {
        if (code != null) {
            Matcher matcher = SCENE_CLASS.matcher(code);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return EXPECTED_SCENE_NAME;
    }

    public static List<String> validateStructure(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            violations.add("Code is empty");
            return violations;
        }

        if (!code.contains("from manim import")) {
            violations.add("Missing 'from manim import' statement");
        }
        if (!MAIN_SCENE_CLASS.matcher(code).find()) {
            violations.add("Scene class must be named MainScene");
        }
        if (!code.contains("def construct(")) {
            violations.add("Missing construct() method");
        }

        String nonAsciiEvidence = CodeValidationSupport.findFirstMatchEvidence(code, NON_ASCII_IDENTIFIER);
        if (nonAsciiEvidence != null) {
            violations.add("Contains non-ASCII class, method, or variable identifiers"
                    + " (" + nonAsciiEvidence + ")");
        }

        return violations;
    }

    public static List<String> validateManimRules(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return violations;
        }

        String rule1Evidence = CodeValidationSupport.findFirstMatchEvidence(code, RULE1_VIOLATION);
        if (rule1Evidence != null) {
            violations.add("Rule 1 violation: stores mobjects on instance fields across scenes"
                    + " (" + rule1Evidence + ")");
        }

        String rule3Evidence = CodeValidationSupport.findFirstMatchEvidence(code, RULE3_VIOLATION);
        if (rule3Evidence != null) {
            violations.add("Rule 3 violation: hardcoded MathTex subobject indexing"
                    + " (" + rule3Evidence + ")");
        }

        return violations;
    }

    public static List<String> validateFull(String code) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateStructure(code));
        violations.addAll(validateManimRules(code));
        return violations;
    }

    public static boolean hasMainSceneClass(String code) {
        return code != null && MAIN_SCENE_CLASS.matcher(code).find();
    }

    public static int countLines(String code) {
        return CodeValidationSupport.countLines(code);
    }
}
