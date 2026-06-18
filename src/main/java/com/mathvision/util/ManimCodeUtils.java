package com.mathvision.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
            "class\\s+MainScene\\s*\\([^)]*\\b(?:[A-Za-z_][A-Za-z0-9_]*)?Scene\\b[^)]*\\)");

    private static final Pattern ANY_SCENE_CLASS = Pattern.compile(
            "class\\s+[^\\s(]+\\s*\\(([^)]*\\b(?:[A-Za-z_][A-Za-z0-9_]*)?Scene\\b[^)]*)\\)");

    private static final Pattern SCENE_CLASS = Pattern.compile(
            "class\\s+(\\w+)\\s*\\([^)]*\\b(?:[A-Za-z_][A-Za-z0-9_]*)?Scene\\b[^)]*\\)");

    private static final Pattern SCENE_METHOD_DEF = Pattern.compile(
            "^(\\s*)def\\s+(scene_[A-Za-z0-9_]*)\\s*\\(\\s*self\\s*\\)\\s*:");

    private static final Pattern CONSTRUCT_METHOD_DEF = Pattern.compile(
            "^(\\s*)def\\s+construct\\s*\\(\\s*self\\s*\\)\\s*:");

    private static final Pattern SELF_SCENE_CALL = Pattern.compile(
            "\\bself\\.(scene_[A-Za-z0-9_]*)\\s*\\(");

    private static final Pattern STATIC_INDEXING_VIOLATION = Pattern.compile(
            "\\w+\\[\\d+\\]\\[\\d+:\\d+\\]");

    private static final Pattern UNSAFE_SET_POINTS_CALL = Pattern.compile(
            "\\.set_points\\s*\\(");

    private static final Pattern TEXT_CONSTRUCTOR_PATTERN = Pattern.compile(
            "\\b(Text|Tex|MathTex)\\s*\\(\\s*(?:r|rf|fr)?([\"'])(.*?)\\2",
            Pattern.DOTALL
    );

    private static final Pattern QUALIFIED_METHOD_CALL_PATTERN = Pattern.compile(
            "\\b((?:[A-Za-z_][A-Za-z0-9_]*\\.)+)([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
    );

    private static final Pattern IMPORT_LINE_PATTERN = Pattern.compile("^\\s*import\\s+(.+)$");
    private static final Pattern FROM_IMPORT_LINE_PATTERN = Pattern.compile(
            "^\\s*from\\s+([A-Za-z_][A-Za-z0-9_\\.]*)\\s+import\\s+(.+)$");

    private static final Set<String> SKIPPED_RECEIVERS = Set.of(
            "self",
            "cls"
    );

    private static final Set<String> COMMON_EXTERNAL_MODULE_RECEIVERS = Set.of(
            "math",
            "np",
            "numpy",
            "random",
            "statistics"
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
     * Builds the canonical Python method name for a storyboard scene.
     */
    public static String buildSceneMethodName(String sceneId, String title, int index) {
        return "scene_" + (index + 1);
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

        violations.addAll(validateMainSceneMethodStructure(manimCode));

        return violations;
    }

    private static List<String> validateMainSceneMethodStructure(String manimCode) {
        List<String> violations = new ArrayList<>();
        if (manimCode == null || manimCode.isBlank()) {
            return violations;
        }

        String[] lines = manimCode.split("\\R", -1);
        int classLine = findMainSceneClassLine(lines);
        if (classLine < 0) {
            return violations;
        }
        int classIndent = countLeadingSpaces(lines[classLine]);
        int classEnd = findBlockEnd(lines, classLine + 1, classIndent);

        Set<String> topLevelSceneMethods = new LinkedHashSet<>();
        List<MethodBlock> classSceneMethods = new ArrayList<>();
        MethodBlock construct = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] != null ? lines[i] : "";
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }

            Matcher sceneMatcher = SCENE_METHOD_DEF.matcher(line);
            if (sceneMatcher.find()) {
                String name = sceneMatcher.group(2);
                int indent = countLeadingSpaces(line);
                if (i > classLine && i < classEnd && indent == classIndent + 4) {
                    classSceneMethods.add(new MethodBlock(name, i, findBlockEnd(lines, i + 1, indent)));
                } else if (indent == 0) {
                    topLevelSceneMethods.add(name);
                }
                continue;
            }

            Matcher constructMatcher = CONSTRUCT_METHOD_DEF.matcher(line);
            if (constructMatcher.find()) {
                int indent = countLeadingSpaces(line);
                if (i > classLine && i < classEnd && indent == classIndent + 4) {
                    construct = new MethodBlock("construct", i, findBlockEnd(lines, i + 1, indent));
                }
            }
        }

        Set<String> classMethodNames = new LinkedHashSet<>();
        for (MethodBlock method : classSceneMethods) {
            classMethodNames.add(method.name);
            if (!hasExecutableMethodBody(lines, method)) {
                violations.add("MainScene scene method '" + method.name + "' is empty or only contains pass");
            }
        }

        for (String topLevelMethod : topLevelSceneMethods) {
            if (classMethodNames.contains(topLevelMethod)) {
                violations.add("Scene method '" + topLevelMethod
                        + "' is implemented outside MainScene while a class-level method with the same name exists");
            } else {
                violations.add("Scene method '" + topLevelMethod + "' is defined outside MainScene");
            }
        }

        if (construct != null) {
            Set<String> calledSceneMethods = findConstructSceneCalls(lines, construct);
            for (String called : calledSceneMethods) {
                if (!classMethodNames.contains(called)) {
                    violations.add("MainScene.construct() calls missing scene method '" + called + "'");
                }
            }
        }

        return violations;
    }

    private static int findMainSceneClassLine(String[] lines) {
        if (lines == null) {
            return -1;
        }
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i] != null ? lines[i].trim() : "";
            if (MAIN_SCENE_CLASS.matcher(trimmed).find()) {
                return i;
            }
        }
        return -1;
    }

    private static int findBlockEnd(String[] lines, int startLine, int parentIndent) {
        if (lines == null) {
            return startLine;
        }
        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i] != null ? lines[i] : "";
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = countLeadingSpaces(line);
            if (indent <= parentIndent) {
                return i;
            }
        }
        return lines.length;
    }

    private static boolean hasExecutableMethodBody(String[] lines, MethodBlock method) {
        if (lines == null || method == null) {
            return false;
        }
        for (int i = method.startLine + 1; i < method.endLine && i < lines.length; i++) {
            String trimmed = lines[i] != null ? lines[i].trim() : "";
            if (trimmed.isBlank()
                    || trimmed.startsWith("#")
                    || trimmed.equals("pass")
                    || trimmed.equals("...")
                    || trimmed.startsWith("\"\"\"")
                    || trimmed.startsWith("'''")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static Set<String> findConstructSceneCalls(String[] lines, MethodBlock construct) {
        Set<String> calls = new LinkedHashSet<>();
        if (lines == null || construct == null) {
            return calls;
        }
        for (int i = construct.startLine + 1; i < construct.endLine && i < lines.length; i++) {
            String line = lines[i] != null ? lines[i] : "";
            Matcher matcher = SELF_SCENE_CALL.matcher(line);
            while (matcher.find()) {
                calls.add(matcher.group(1));
            }
        }
        return calls;
    }

    private static int countLeadingSpaces(String line) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static final class MethodBlock {
        final String name;
        final int startLine;
        final int endLine;

        MethodBlock(String name, int startLine, int endLine) {
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
        }
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

        for (String evidence : findAllUnsafeSetPointsCalls(manimCode)) {
            violations.add("Static rule violation: unsafe VMobject.set_points() call"
                    + " (" + evidence + ")");
        }

        return violations;
    }

    public static List<String> validateManimApiWhitelistWarnings(String manimCode) {
        List<String> warnings = new ArrayList<>();
        if (manimCode == null || manimCode.isBlank()) {
            return warnings;
        }

        for (String evidence : findAllUndocumentedManimMethodCalls(manimCode)) {
            warnings.add("Static rule warning: undocumented Manim API call"
                    + " (" + evidence + ")");
        }

        warnings.addAll(validateTextConstructorSemantics(manimCode));

        return warnings;
    }

    public static List<String> validateFull(String manimCode) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateStructure(manimCode));
        violations.addAll(validateManimRules(manimCode));
        return violations;
    }

    public static List<String> validateFullWarnings(String manimCode) {
        return validateManimApiWhitelistWarnings(manimCode);
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
        Set<String> importedReceivers = extractImportedReceivers(manimCode);
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

            Matcher matcher = QUALIFIED_METHOD_CALL_PATTERN.matcher(line);
            while (matcher.find()) {
                String receiver = matcher.group(1);
                String methodName = matcher.group(2);
                String receiverRoot = extractReceiverRoot(receiver);
                if (receiverRoot == null
                        || SKIPPED_RECEIVERS.contains(receiverRoot)
                        || importedReceivers.contains(receiverRoot)) {
                    continue;
                }
                if (!methodName.contains("_") && !COMMON_EXTERNAL_MODULE_RECEIVERS.contains(receiverRoot)) {
                    continue;
                }
                if (!documented.contains(methodName)) {
                    String fragment = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                    evidences.add("line " + (i + 1) + ": " + receiver + "." + methodName + "() - " + fragment);
                }
            }
        }
        return evidences;
    }

    private static List<String> findAllUnsafeSetPointsCalls(String manimCode) {
        List<String> evidences = new ArrayList<>();
        if (manimCode == null || manimCode.isBlank()) {
            return evidences;
        }
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
            if (UNSAFE_SET_POINTS_CALL.matcher(line).find()) {
                String fragment = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
                evidences.add("line " + (i + 1) + ": " + fragment);
            }
        }
        return evidences;
    }

    private static Set<String> extractImportedReceivers(String manimCode) {
        Set<String> receivers = new LinkedHashSet<>(SKIPPED_RECEIVERS);
        if (manimCode == null || manimCode.isBlank()) {
            return receivers;
        }

        String[] lines = manimCode.split("\\R");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.startsWith("#")) {
                continue;
            }

            Matcher importMatcher = IMPORT_LINE_PATTERN.matcher(line);
            if (importMatcher.matches()) {
                addImportedModuleAliases(receivers, importMatcher.group(1));
                continue;
            }

            Matcher fromImportMatcher = FROM_IMPORT_LINE_PATTERN.matcher(line);
            if (fromImportMatcher.matches()) {
                addImportedNames(receivers, fromImportMatcher.group(2));
            }
        }
        return receivers;
    }

    private static void addImportedModuleAliases(Set<String> receivers, String importClause) {
        if (importClause == null || importClause.isBlank()) {
            return;
        }

        for (String segment : importClause.split(",")) {
            String alias = extractImportAlias(segment);
            if (!alias.isBlank()) {
                receivers.add(alias);
            }
        }
    }

    private static void addImportedNames(Set<String> receivers, String importClause) {
        if (importClause == null || importClause.isBlank()) {
            return;
        }

        for (String segment : importClause.split(",")) {
            String alias = extractImportAlias(segment);
            if (!alias.isBlank()) {
                receivers.add(alias);
            }
        }
    }

    private static String extractImportAlias(String importToken) {
        if (importToken == null) {
            return "";
        }
        String normalized = importToken.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        int aliasIndex = normalized.indexOf(" as ");
        if (aliasIndex >= 0) {
            return normalized.substring(aliasIndex + 4).trim();
        }

        int dotIndex = normalized.indexOf('.');
        if (dotIndex >= 0) {
            return normalized.substring(0, dotIndex).trim();
        }

        return normalized;
    }

    private static String extractReceiverRoot(String receiver) {
        if (receiver == null || receiver.isBlank()) {
            return null;
        }

        String normalized = receiver.trim();
        int dotIndex = normalized.indexOf('.');
        if (dotIndex >= 0) {
            return normalized.substring(0, dotIndex).trim();
        }
        return normalized;
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

            if ("Text".equals(constructor)
                    && looksLikeLatexMath(normalizedContent)
                    && !looksLikeProseWithInlineMathLabel(normalizedContent)) {
                issues.add("Static rule warning: Text constructor with math-like content, consider MathTex"
                        + " (line " + line + ": " + summarizeSnippet(normalizedContent) + ")");
                continue;
            }

            if ("Tex".equals(constructor) && looksLikeMathModeContent(normalizedContent)) {
                issues.add("Static rule warning: Tex constructor with math-mode content, consider MathTex"
                        + " (line " + line + ": " + summarizeSnippet(normalizedContent) + ")");
                continue;
            }

            if ("MathTex".equals(constructor) && looksLikePlainSentence(normalizedContent)) {
                issues.add("Static rule warning: MathTex constructor with plain-language content, consider Text"
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
        return looksLikeFormulaExpression(content);
    }

    private static boolean looksLikeFormulaExpression(String content) {
        String normalized = content.replaceAll("\\s+", "");
        if (normalized.isEmpty() || normalized.matches(".*[\\u4E00-\\u9FFF].*")) {
            return false;
        }
        return normalized.matches(".*[A-Za-z0-9)][=+*/<>≤≥-][A-Za-z0-9(].*");
    }

    private static boolean looksLikeProseWithInlineMathLabel(String content) {
        if (content == null || !content.matches(".*[\\u4E00-\\u9FFF].*")) {
            return false;
        }
        if (content.contains("$")
                || content.matches(".*\\\\[a-zA-Z]{2,}.*")
                || content.matches(".*[=+*/<>≤≥∑∫√].*")) {
            return false;
        }

        String proseWithoutLabels = content
                .replaceAll("\\b[A-Za-z][\\u2070-\\u209F\\u2032'’]*\\b", "")
                .replaceAll("\\b[A-Za-z]_[A-Za-z0-9]+\\b", "")
                .replaceAll("[\\u2070-\\u209F\\u2032'’]", "")
                .trim();
        return proseWithoutLabels.matches(".*[\\u4E00-\\u9FFF].*");
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
        return token.contains("’") || token.contains("'");
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
