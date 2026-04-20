package com.mathvision.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /**
     * Matches {@code .method_name(} where the method name is snake_case
     * (contains at least one underscore). Used to detect undocumented Manim
     * instance method calls.
     */
    private static final Pattern SNAKE_CASE_METHOD_CALL = Pattern.compile(
            "\\.(([a-z][a-z0-9]*_[a-z0-9_]*))\\s*\\(");

    /** Python / NumPy builtins with underscores that are not Manim methods. */
    private static final Set<String> PYTHON_BUILTIN_SNAKE_METHODS = Set.of(
            "is_integer", "as_integer_ratio", "from_bytes", "to_bytes",
            "read_text", "write_text", "join_path",
            "set_default", "from_iterable",
            "named_children", "named_parameters",
            "start_with", "ends_with"
    );

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

    /**
     * Builds a Python method name for a scene, e.g. "scene_1_setup_coordinates".
     */
    public static String buildSceneMethodName(String sceneId, String title, int index) {
        String base = "scene_" + (index + 1);
        String suffix = "";
        if (title != null && !title.isBlank()) {
            suffix = "_" + title.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_|_$", "")
                    .replaceAll("_{2,}", "_");
            if (suffix.length() > 30) {
                suffix = suffix.substring(0, 30).replaceAll("_$", "");
            }
        }
        return base + suffix;
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

        // Rule 4: undocumented Manim instance method calls
        String rule4Evidence = findUndocumentedMethodCall(manimCode);
        if (rule4Evidence != null) {
            violations.add("Rule 4 violation: undocumented Manim API call"
                    + " (" + rule4Evidence + ")");
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

    /**
     * Scans code for snake_case method calls not in the documented whitelist.
     * Returns evidence string for the first violation, or null if clean.
     */
    static String findUndocumentedMethodCall(String manimCode) {
        Set<String> documented = ManimValidationSupport.documentedInstanceMethodNames();
        String[] lines = manimCode.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // Skip comments and string-only lines
            if (line.startsWith("#")) {
                continue;
            }
            Matcher matcher = SNAKE_CASE_METHOD_CALL.matcher(line);
            while (matcher.find()) {
                String methodName = matcher.group(1);
                if (!documented.contains(methodName)
                        && !PYTHON_BUILTIN_SNAKE_METHODS.contains(methodName)) {
                    String fragment = line.length() > 80 ? line.substring(0, 80) + "..." : line;
                    return "line " + (i + 1) + ": ." + methodName + "() — " + fragment;
                }
            }
        }
        return null;
    }
}
