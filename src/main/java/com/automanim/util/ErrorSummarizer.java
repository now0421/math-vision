package com.automanim.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for error summarization, traceback handling, and error classification.
 *
 * Consolidates patterns previously in RenderNode for reuse across fix-related nodes.
 */
public final class ErrorSummarizer {

    private ErrorSummarizer() {}

    private static final int MAX_TRACEBACK_LINES = 30;
    private static final int TRACEBACK_CONTEXT_RADIUS = 4;
    private static final int MAX_STDOUT_ERROR_LINES = 12;
    private static final String TRACEBACK_MARKER = "Traceback (most recent call last)";

    private static final Pattern ERROR_SIGNATURE_PATTERN = Pattern.compile(
            "\\b(?:[A-Za-z_][A-Za-z0-9_]*Error|[A-Za-z_][A-Za-z0-9_]*Exception)\\s*:\\s*.+");

    private static final List<Pattern> NON_CODE_ERROR_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)no module named"),
            Pattern.compile("(?i)command not found"),
            Pattern.compile("(?i)permission denied"),
            Pattern.compile("(?i)out of memory"),
            Pattern.compile("(?i)disk quota"),
            Pattern.compile("(?i)segmentation fault"),
            Pattern.compile("(?i)killed"),
            Pattern.compile("(?i)cannot allocate memory"),
            Pattern.compile("(?i)ffmpeg.*not found"),
            Pattern.compile("(?i)latex.*not found"),
            Pattern.compile("(?i)dvisvgm.*not found")
    );

    /**
     * Checks if the error indicates an environment problem rather than a code bug.
     */
    public static boolean isEnvironmentError(String error) {
        if (error == null || error.isBlank()) {
            return false;
        }
        for (Pattern pattern : NON_CODE_ERROR_PATTERNS) {
            if (pattern.matcher(error).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Summarizes a traceback, keeping the most relevant lines.
     */
    public static String summarizeTraceback(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }

        int markerIndex = stderr.lastIndexOf(TRACEBACK_MARKER);
        if (markerIndex < 0) {
            return extractFocusedError(stderr);
        }

        String tracebackSection = stderr.substring(markerIndex);
        String[] lines = tracebackSection.split("\\R");

        if (lines.length <= MAX_TRACEBACK_LINES) {
            return tracebackSection.trim();
        }

        List<String> result = new ArrayList<>();
        int lastErrorIndex = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (ERROR_SIGNATURE_PATTERN.matcher(lines[i]).find()) {
                lastErrorIndex = i;
                break;
            }
        }

        if (lastErrorIndex < 0) {
            lastErrorIndex = lines.length - 1;
        }

        result.add(lines[0]);

        int contextStart = Math.max(1, lastErrorIndex - TRACEBACK_CONTEXT_RADIUS);
        int contextEnd = Math.min(lines.length - 1, lastErrorIndex + TRACEBACK_CONTEXT_RADIUS);

        if (contextStart > 1) {
            result.add("  ... (" + (contextStart - 1) + " lines omitted) ...");
        }

        for (int i = contextStart; i <= contextEnd; i++) {
            result.add(lines[i]);
        }

        if (contextEnd < lines.length - 1) {
            for (int i = contextEnd + 1; i < lines.length; i++) {
                if (ERROR_SIGNATURE_PATTERN.matcher(lines[i]).find()) {
                    result.add(lines[i]);
                }
            }
        }

        return String.join("\n", result);
    }

    /**
     * Extracts the focused error message from stderr.
     */
    public static String extractFocusedError(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }

        String[] lines = stderr.split("\\R");
        List<String> errorLines = new ArrayList<>();

        for (int i = lines.length - 1; i >= 0 && errorLines.size() < MAX_STDOUT_ERROR_LINES; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (ERROR_SIGNATURE_PATTERN.matcher(line).find()) {
                errorLines.add(0, line);
                for (int j = i - 1; j >= 0 && errorLines.size() < MAX_STDOUT_ERROR_LINES; j--) {
                    String contextLine = lines[j].trim();
                    if (contextLine.isEmpty()) {
                        break;
                    }
                    errorLines.add(0, contextLine);
                }
                break;
            }
            errorLines.add(0, line);
        }

        return String.join("\n", errorLines);
    }

    /**
     * Extracts focused error from both stdout and stderr.
     */
    public static String extractFocusedError(String stdout, String stderr) {
        List<String> sections = new ArrayList<>();

        String stdoutSummary = extractStdoutErrors(stdout);
        if (!stdoutSummary.isBlank()) {
            sections.add("=== stdout highlights ===\n" + stdoutSummary);
        }

        String stderrSummary = summarizeTraceback(stderr);
        if (!stderrSummary.isBlank()) {
            sections.add("=== stderr traceback ===\n" + stderrSummary);
        }

        if (!sections.isEmpty()) {
            return String.join("\n\n", sections);
        }

        return tailLines(combineErrorStreams(stdout, stderr), MAX_TRACEBACK_LINES);
    }

    /**
     * Extracts error-related lines from stdout.
     */
    public static String extractStdoutErrors(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return "";
        }

        String[] lines = stdout.split("\\R");
        List<String> errorLines = new ArrayList<>();

        for (int i = 0; i < lines.length && errorLines.size() < MAX_STDOUT_ERROR_LINES; i++) {
            String line = lines[i];
            if (line.matches(".*(?i)(\\bERROR\\b|exception|traceback|not in the script|latex compilation error|context of error).*")) {
                errorLines.add(line);
                for (int j = i + 1; j < lines.length && j <= i + 3 && errorLines.size() < MAX_STDOUT_ERROR_LINES; j++) {
                    String nextLine = lines[j];
                    if (!nextLine.contains("%|") && !nextLine.trim().startsWith("Animation ")) {
                        errorLines.add(nextLine);
                    }
                }
            }
        }

        return String.join("\n", errorLines).trim();
    }

    /**
     * Combines stdout and stderr into a single error string.
     */
    public static String combineErrorStreams(String stdout, String stderr) {
        List<String> sections = new ArrayList<>();
        if (stdout != null && !stdout.isBlank()) {
            sections.add("[stdout]\n" + stdout.strip());
        }
        if (stderr != null && !stderr.isBlank()) {
            sections.add("[stderr]\n" + stderr.strip());
        }
        return String.join("\n\n", sections);
    }

    /**
     * Summarizes an error into a short signature for deduplication.
     */
    public static String summarizeSignature(String focusedError) {
        if (focusedError == null || focusedError.isBlank()) {
            return "";
        }

        String[] lines = focusedError.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            Matcher matcher = ERROR_SIGNATURE_PATTERN.matcher(line);
            if (matcher.find()) {
                String signature = matcher.group().trim();
                return signature.length() > 200 ? signature.substring(0, 200) : signature;
            }
        }

        String normalized = focusedError.replaceAll("\\s+", " ").trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    /**
     * Collapses a longer issue summary into a short single-line history entry.
     */
    public static String compactSummary(String summary, int maxLength) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        String normalized = summary.replaceAll("\\s+", " ").trim();
        if (maxLength <= 0 || normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    /**
     * Returns the last N lines of text.
     */
    private static String tailLines(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] lines = text.split("\\R");
        if (lines.length <= maxLines) {
            return text.strip();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * Extracts the last traceback chunk from output.
     */
    public static String extractLastTracebackChunk(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }

        int markerIndex = output.lastIndexOf(TRACEBACK_MARKER);
        if (markerIndex < 0) {
            return "";
        }

        return output.substring(markerIndex).trim();
    }

    /**
     * Classifies an error into categories for fix routing.
     */
    public static ErrorCategory classifyError(String error) {
        if (error == null || error.isBlank()) {
            return ErrorCategory.UNKNOWN;
        }

        if (isEnvironmentError(error)) {
            return ErrorCategory.ENVIRONMENT;
        }

        String lower = error.toLowerCase();

        if (lower.contains("syntaxerror") || lower.contains("indentationerror")) {
            return ErrorCategory.SYNTAX;
        }
        if (lower.contains("nameerror") || lower.contains("attributeerror")) {
            return ErrorCategory.NAME_RESOLUTION;
        }
        if (lower.contains("typeerror") || lower.contains("valueerror")) {
            return ErrorCategory.TYPE_VALUE;
        }
        if (lower.contains("indexerror") || lower.contains("keyerror")) {
            return ErrorCategory.INDEX_KEY;
        }
        if (lower.contains("importerror") || lower.contains("modulenotfounderror")) {
            return ErrorCategory.IMPORT;
        }

        return ErrorCategory.RUNTIME;
    }

    /**
     * Error categories for fix routing decisions.
     */
    public enum ErrorCategory {
        SYNTAX,
        NAME_RESOLUTION,
        TYPE_VALUE,
        INDEX_KEY,
        IMPORT,
        RUNTIME,
        ENVIRONMENT,
        UNKNOWN
    }
}
