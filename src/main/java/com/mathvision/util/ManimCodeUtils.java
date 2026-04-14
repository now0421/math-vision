package com.mathvision.util;

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

    public static String enforceMainSceneName(String manimCode) {
        if (manimCode == null || manimCode.isBlank()) {
            return manimCode;
        }
        return ANY_SCENE_CLASS.matcher(manimCode)
                .replaceFirst("class MainScene($1)");
    }

    public static String expectedSceneName() {
        return EXPECTED_SCENE_NAME;
    }

    public static String extractSceneName(String manimCode, String fallback) {
        if (manimCode != null) {
            Matcher matcher = SCENE_CLASS.matcher(manimCode);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return EXPECTED_SCENE_NAME;
    }

    public static List<String> validateStructure(String manimCode) {
        List<String> violations = new ArrayList<>();
        if (manimCode == null || manimCode.isBlank()) {
            violations.add("Code is empty");
            return violations;
        }

        if (!manimCode.contains("from manim import")) {
            violations.add("Missing 'from manim import' statement");
        }
        if (!MAIN_SCENE_CLASS.matcher(manimCode).find()) {
            violations.add("Scene class must be named MainScene");
        }
        if (!manimCode.contains("def construct(")) {
            violations.add("Missing construct() method");
        }

        return violations;
    }

    public static List<String> validateManimRules(String manimCode) {
        List<String> violations = new ArrayList<>();
        if (manimCode == null || manimCode.isBlank()) {
            return violations;
        }

        String rule1Evidence = CodeValidationSupport.findFirstMatchEvidence(manimCode, RULE1_VIOLATION);
        if (rule1Evidence != null) {
            violations.add("Rule 1 violation: stores mobjects on instance fields across scenes"
                    + " (" + rule1Evidence + ")");
        }

        String rule3Evidence = CodeValidationSupport.findFirstMatchEvidence(manimCode, RULE3_VIOLATION);
        if (rule3Evidence != null) {
            violations.add("Rule 3 violation: hardcoded MathTex subobject indexing"
                    + " (" + rule3Evidence + ")");
        }

        return violations;
    }

    public static List<String> validateFull(String manimCode) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateStructure(manimCode));
        violations.addAll(validateManimRules(manimCode));
        return violations;
    }

    public static boolean hasMainSceneClass(String manimCode) {
        return manimCode != null && MAIN_SCENE_CLASS.matcher(manimCode).find();
    }

    public static int countLines(String manimCode) {
        return CodeValidationSupport.countLines(manimCode);
    }
}
