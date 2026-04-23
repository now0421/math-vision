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
    private static final Pattern TEXT_CONSTRUCTOR_PATTERN = Pattern.compile(
            "\\b(Text|Tex|MathTex)\\s*\\(\\s*(?:r|rf|fr)?([\"'])(.*?)\\2",
            Pattern.DOTALL
    );

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

        violations.addAll(validateTextConstructorSemantics(manimCode));

        return violations;
    }

    public static List<String> validateFull(String manimCode) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateStructure(manimCode));
        violations.addAll(validateManimRules(manimCode));
        return violations;
    }

    public static List<String> validateRenderPreflight(String manimCode) {
        return validateFull(manimCode);
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

    static List<String> validateTextConstructorSemantics(String manimCode) {
        List<String> issues = new ArrayList<>();
        if (manimCode == null || manimCode.isBlank()) {
            return issues;
        }

        Matcher matcher = TEXT_CONSTRUCTOR_PATTERN.matcher(manimCode);
        int line = 1;
        int previousIndex = 0;
        while (matcher.find()) {
            line += countNewlines(manimCode, previousIndex, matcher.start());
            previousIndex = matcher.start();

            String constructor = matcher.group(1);
            String content = matcher.group(3);
            String normalizedContent = content != null ? content.trim() : "";
            if (normalizedContent.isBlank()) {
                continue;
            }

            if ("Text".equals(constructor) && looksLikeLatexMath(normalizedContent)) {
                issues.add("Text constructor mismatch: math-like content should not use Text"
                        + " (line " + line + ": " + summarizeSnippet(normalizedContent) + ")");
                continue;
            }

            if ("Tex".equals(constructor) && looksLikeMathModeContent(normalizedContent)) {
                issues.add("Tex constructor mismatch: math-mode content should use MathTex or explicit math mode"
                        + " (line " + line + ": " + summarizeSnippet(normalizedContent) + ")");
                continue;
            }

            if ("MathTex".equals(constructor) && looksLikePlainSentence(normalizedContent)) {
                issues.add("MathTex constructor mismatch: plain-language sentence should not use MathTex"
                        + " (line " + line + ": " + summarizeSnippet(normalizedContent) + ")");
            }
        }

        return issues;
    }

    private static int countNewlines(String text, int start, int end) {
        int count = 0;
        for (int i = Math.max(0, start); i < Math.min(text.length(), end); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static boolean looksLikeLatexMath(String content) {
        return looksLikeMathModeContent(content);
    }

    private static boolean looksLikeMathModeContent(String content) {
        // Structural math notation: superscripts, subscripts
        if (content.contains("^") || content.contains("_")) return true;
        // LaTeX control sequences: \ + alphabetic chars (matches ALL LaTeX commands)
        // Exclude Python escape sequences ONLY when the backslash is followed by exactly
        // one of the escape chars and NOT more letters (e.g. \n is Python, \name is LaTeX)
        if (content.matches(".*\\\\[a-zA-Z]{2,}.*")) return true;
        if (content.matches(".*\\\\[a-zA-Z].*") && !content.matches(".*\\\\[ntrfu0](?![a-zA-Z]).*")) return true;
        // Math-mode delimiter
        if (content.contains("$")) return true;
        // Unicode math symbol ranges:
        // U+2200-U+22FF = Mathematical Operators
        // U+0391-U+03C9 = Greek letters
        // U+2070-U+209F = Superscripts and Subscripts
        if (content.matches(".*[\\u2200-\\u22FF\\u0391-\\u03C9\\u2070-\\u209F].*")) return true;
        return false;
    }

    /**
     * Public structural math indicator check, reused by ErrorSummarizer
     * for LaTeX offending token extraction.
     */
    public static boolean containsMathIndicator(String token) {
        if (token.contains("^") || token.contains("_") || token.contains("*")) return true;
        // LaTeX command (2+ letters after backslash), or 1 letter that's not a Python escape
        if (token.matches(".*\\\\[a-zA-Z]{2,}.*")) return true;
        if (token.matches(".*\\\\[a-zA-Z].*") && !token.matches(".*\\\\[ntrfu0](?![a-zA-Z]).*")) return true;
        if (token.matches(".*[\\u2200-\\u22FF\\u0391-\\u03C9].*")) return true;
        if (token.contains("′") || token.contains("'")) return true;
        return false;
    }

    private static boolean looksLikePlainSentence(String content) {
        if (looksLikeMathModeContent(content)) {
            return false;
        }
        // Brace grouping is a strong LaTeX structural indicator
        if (content.contains("{")) {
            return false;
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() < 12) {
            return false;
        }
        String[] words = normalized.split(" ");
        return words.length >= 3 && normalized.matches(".*[A-Za-z]{3,}.*");
    }

    private static String summarizeSnippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80) + "...";
    }
}
