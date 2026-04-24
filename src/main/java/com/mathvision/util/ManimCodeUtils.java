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

    private static final Pattern STATIC_INDEXING_VIOLATION = Pattern.compile(
            "\\w+\\[\\d+\\]\\[\\d+:\\d+\\]");

    private static final Pattern TEXT_CONSTRUCTOR_PATTERN = Pattern.compile(
            "\\b(Text|Tex|MathTex)\\s*\\(\\s*(?:r|rf|fr)?([\"'])(.*?)\\2",
            Pattern.DOTALL
    );

    private static final Pattern MANIM_METHOD_CALL_PATTERN = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
    );

    private static final Set<String> SKIPPED_RECEIVERS = Set.of(
            "self",
            "cls"
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

        for (String evidence : CodeValidationSupport.findAllMatchEvidences(manimCode, STATIC_INDEXING_VIOLATION)) {
            violations.add("Static rule violation: hardcoded MathTex subobject indexing"
                    + " (" + evidence + ")");
        }

        for (String evidence : findAllUndocumentedManimMethodCalls(manimCode)) {
            violations.add("Static rule violation: undocumented Manim API call"
                    + " (" + evidence + ")");
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

    public static boolean hasMainSceneClass(String manimCode) {
        return manimCode != null && MAIN_SCENE_CLASS.matcher(manimCode).find();
    }

    public static int countLines(String manimCode) {
        return CodeValidationSupport.countLines(manimCode);
    }

    /**
     * Scans code for undocumented snake_case method calls while ignoring
     * user-defined helpers on {@code self} or class-level receivers.
     */
    static List<String> findAllUndocumentedManimMethodCalls(String manimCode) {
        List<String> evidences = new ArrayList<>();
        Set<String> documented = ManimValidationSupport.documentedInstanceMethodNames();
        String[] lines = manimCode.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }

            Matcher matcher = MANIM_METHOD_CALL_PATTERN.matcher(line);
            while (matcher.find()) {
                String receiver = matcher.group(1);
                String methodName = matcher.group(2);
                if (receiver == null || SKIPPED_RECEIVERS.contains(receiver)) {
                    continue;
                }
                if (!documented.contains(methodName)) {
                    String fragment = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                    evidences.add("line " + (i + 1) + ": " + receiver + "." + methodName + "() — " + fragment);
                }
            }
        }
        return evidences;
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
        if (content.contains("^") || content.contains("_")) {
            return true;
        }
        if (content.matches(".*\\\\[a-zA-Z]{2,}.*")) {
            return true;
        }
        if (content.matches(".*\\\\[a-zA-Z].*") && !content.matches(".*\\\\[ntrfu0](?![a-zA-Z]).*")) {
            return true;
        }
        if (content.contains("$")) {
            return true;
        }
        if (content.matches(".*[\\u2200-\\u22FF\\u0391-\\u03C9\\u2070-\\u209F].*")) {
            return true;
        }
        return false;
    }

    /**
     * Public structural math indicator check, reused by ErrorSummarizer
     * for LaTeX offending token extraction.
     */
    public static boolean containsMathIndicator(String token) {
        if (token.contains("^") || token.contains("_") || token.contains("*")) {
            return true;
        }
        if (token.matches(".*\\\\[a-zA-Z]{2,}.*")) {
            return true;
        }
        if (token.matches(".*\\\\[a-zA-Z].*") && !token.matches(".*\\\\[ntrfu0](?![a-zA-Z]).*")) {
            return true;
        }
        if (token.matches(".*[\\u2200-\\u22FF\\u0391-\\u03C9].*")) {
            return true;
        }
        return token.contains("鈥") || token.contains("'");
    }

    private static boolean looksLikePlainSentence(String content) {
        if (looksLikeMathModeContent(content)) {
            return false;
        }
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
