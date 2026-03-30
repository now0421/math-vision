package com.automanim.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared utilities for GeoGebra command post-processing and static prechecks.
 */
public final class GeoGebraCodeUtils {

    private GeoGebraCodeUtils() {}

    private static final Pattern PYTHON_OR_MANIM_SYNTAX = Pattern.compile(
            "\\b(?:from\\s+manim\\s+import|class\\s+\\w+\\s*\\(|def\\s+\\w+\\s*\\(|self\\.|import\\s+[A-Za-z_])");

    private static final Pattern JAVASCRIPT_SYNTAX = Pattern.compile(
            "\\b(?:const|let|var|function)\\b|=>|document\\.|window\\.");

    private static final Pattern FULL_WIDTH_PUNCTUATION = Pattern.compile(
            "[\\uFF0C\\uFF1B\\uFF1A\\u3002\\uFF01\\uFF1F\\uFF08\\uFF09"
                    + "\\u3010\\u3011\\u300A\\u300B\\u201C\\u201D\\u2018\\u2019]");

    private static final Pattern NON_ASCII_IDENTIFIER = Pattern.compile(
            "^[^\\x00-\\x7F\\s#/]+\\s*[:=(]|[:=]\\s*[^\\x00-\\x7F][^\\s,)]*");

    private static final Pattern ASSIGNMENT_OR_NAMED_OBJECT = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_]*)\\s*[:=]\\s*(.+)$");

    private static final Pattern COMMAND_CALL = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_]*)\\s*\\(.*\\)$");

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

    public static List<String> extractCommands(String code) {
        String normalized = extractCode(code);
        List<String> commands = new ArrayList<>();
        for (String line : normalized.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            commands.add(trimmed);
        }
        return commands;
    }

    public static List<String> validateStructure(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            violations.add("Code is empty");
            return violations;
        }

        List<String> commands = extractCommands(code);
        if (commands.isEmpty()) {
            violations.add("No executable GeoGebra commands found");
            return violations;
        }

        String pythonEvidence = CodeValidationSupport.findFirstMatchEvidence(code, PYTHON_OR_MANIM_SYNTAX);
        if (pythonEvidence != null) {
            violations.add("Contains Python or Manim syntax (" + pythonEvidence + ")");
        }

        String javascriptEvidence = CodeValidationSupport.findFirstMatchEvidence(code, JAVASCRIPT_SYNTAX);
        if (javascriptEvidence != null) {
            violations.add("Contains JavaScript syntax (" + javascriptEvidence + ")");
        }

        String invalidCommandEvidence = findFirstInvalidCommandEvidence(commands);
        if (invalidCommandEvidence != null) {
            violations.add("Contains a line that does not look like a GeoGebra command ("
                    + invalidCommandEvidence + ")");
        }

        String unbalancedEvidence = findFirstUnbalancedCommandEvidence(commands);
        if (unbalancedEvidence != null) {
            violations.add("Contains unbalanced quotes or delimiters (" + unbalancedEvidence + ")");
        }

        return violations;
    }

    public static List<String> validateGeoGebraRules(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return violations;
        }

        String punctuationEvidence = CodeValidationSupport.findFirstMatchEvidence(code, FULL_WIDTH_PUNCTUATION);
        if (punctuationEvidence != null) {
            violations.add("Contains full-width punctuation that GeoGebra command parsing may reject"
                    + " (" + punctuationEvidence + ")");
        }

        String nonAsciiEvidence = CodeValidationSupport.findFirstMatchEvidence(code, NON_ASCII_IDENTIFIER);
        if (nonAsciiEvidence != null) {
            violations.add("Contains non-ASCII GeoGebra identifiers or command names"
                    + " (" + nonAsciiEvidence + ")");
        }

        String multiCommandEvidence = findFirstSemicolonEvidence(extractCommands(code));
        if (multiCommandEvidence != null) {
            violations.add("Contains multiple commands on one line; keep one GeoGebra command per line"
                    + " (" + multiCommandEvidence + ")");
        }

        return violations;
    }

    public static List<String> validateFull(String code) {
        List<String> violations = new ArrayList<>();
        violations.addAll(validateStructure(code));
        violations.addAll(validateGeoGebraRules(code));
        return violations;
    }

    public static boolean looksLikeCommandBlock(String code) {
        List<String> commands = extractCommands(code);
        if (commands.isEmpty()) {
            return false;
        }

        int commandLikeLines = 0;
        for (String command : commands) {
            if (looksLikeCommand(command)) {
                commandLikeLines++;
            }
        }
        return commandLikeLines * 2 >= commands.size();
    }

    public static boolean looksLikeCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return ASSIGNMENT_OR_NAMED_OBJECT.matcher(command).matches()
                || COMMAND_CALL.matcher(command).matches();
    }

    private static String findFirstInvalidCommandEvidence(List<String> commands) {
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            if (!looksLikeCommand(command)) {
                return "line " + (i + 1) + ": " + abbreviate(command);
            }
        }
        return null;
    }

    private static String findFirstUnbalancedCommandEvidence(List<String> commands) {
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            if (!hasBalancedDelimiters(command)) {
                return "line " + (i + 1) + ": " + abbreviate(command);
            }
        }
        return null;
    }

    private static String findFirstSemicolonEvidence(List<String> commands) {
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            if (command.contains(";")) {
                return "line " + (i + 1) + ": " + abbreviate(command);
            }
        }
        return null;
    }

    private static boolean hasBalancedDelimiters(String command) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            char previous = i > 0 ? command.charAt(i - 1) : '\0';

            if (ch == '\'' && !inDoubleQuote && previous != '\\') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inSingleQuote && previous != '\\') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (ch == '(') {
                parentheses++;
            } else if (ch == ')') {
                parentheses--;
            } else if (ch == '[') {
                brackets++;
            } else if (ch == ']') {
                brackets--;
            } else if (ch == '{') {
                braces++;
            } else if (ch == '}') {
                braces--;
            }

            if (parentheses < 0 || brackets < 0 || braces < 0) {
                return false;
            }
        }

        return !inSingleQuote
                && !inDoubleQuote
                && parentheses == 0
                && brackets == 0
                && braces == 0;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
