package com.mathvision.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight diagnostics for suspicious mojibake and text-health issues.
 */
public final class TextHealthDiagnostics {

    /** Known mojibake codepoints — kept as a higher-specificity refinement. */
    private static final Pattern MOJIBAKE_PATTERN = Pattern.compile(
            "\\u9225|\\u922d|\\ufffd|\\u00c3.|\\u00e2.|\\u00e6.|\\u00e7.|\\u00ce.|\\u00cf.|\\u00e5.|\\u00f8|\\u2229"
    );

    /**
     * Positive-range allowlist: any character outside these expected ranges
     * is flagged as unexpected, regardless of whether its specific codepoint
     * was previously cataloged.
     */
    private static final Pattern UNEXPECTED_CHAR_PATTERN = Pattern.compile(
            "[^\\x09\\x0A\\x0D\\x20-\\x7E"     // ASCII printable + whitespace
            + "\\u4E00-\\u9FFF"                   // CJK Unified Ideographs
            + "\\u3000-\\u303F"                   // CJK Symbols and Punctuation
            + "\\uFF00-\\uFFEF"                   // Halfwidth and Fullwidth Forms
            + "\\u2200-\\u22FF"                   // Mathematical Operators
            + "\\u0370-\\u03FF"                   // Greek and Coptic
            + "\\u2000-\\u206F"                   // General Punctuation
            + "\\u2070-\\u209F"                   // Superscripts and Subscripts
            + "\\u0080-\\u00FF"                   // Latin-1 Supplement
            + "\\u2900-\\u2AFF"                   // Miscellaneous Mathematical Symbols A/B
            + "]"
    );

    private static final Pattern CONTROL_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final int MAX_SNIPPET_LENGTH = 160;

    private TextHealthDiagnostics() {}

    public static TextHealthReport inspect(String text) {
        if (text == null) {
            return new TextHealthReport(false, false, List.of(), "");
        }

        List<String> issues = new ArrayList<>();
        boolean hasMojibake = false;
        String snippet = "";

        // Broad range check: any character outside expected ranges
        MatchRegion unexpected = findFirst(UNEXPECTED_CHAR_PATTERN, text);
        if (unexpected.found) {
            issues.add("unexpected_character_range");
            char ch = text.charAt(unexpected.start);
            snippet = String.format("U+%04X at pos %d: %s",
                    (int) ch, unexpected.start,
                    buildSnippet(text, unexpected.start, unexpected.end));
        }

        // Higher-specificity mojibake check: upgrade the label if matched
        MatchRegion mojibake = findFirst(MOJIBAKE_PATTERN, text);
        if (mojibake.found) {
            issues.remove("unexpected_character_range");
            issues.add("suspicious_mojibake");
            hasMojibake = true;
            snippet = buildSnippet(text, mojibake.start, mojibake.end);
        }

        MatchRegion control = findFirst(CONTROL_PATTERN, text);
        if (control.found) {
            issues.add("unexpected_control_chars");
            if (snippet.isEmpty()) {
                snippet = buildSnippet(text, control.start, control.end);
            }
        }

        return new TextHealthReport(!issues.isEmpty(), hasMojibake, issues, snippet);
    }

    public static boolean hasSuspiciousEncoding(String text) {
        return inspect(text).hasSuspiciousEncoding();
    }

    public static String summarize(String text) {
        return inspect(text).summary();
    }

    private static MatchRegion findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return MatchRegion.notFound();
        }
        return new MatchRegion(true, matcher.start(), matcher.end());
    }

    private static String buildSnippet(String text, int start, int end) {
        int snippetStart = Math.max(0, start - 24);
        int snippetEnd = Math.min(text.length(), Math.max(end + 24, snippetStart + MAX_SNIPPET_LENGTH));
        String snippet = text.substring(snippetStart, snippetEnd).replaceAll("\\s+", " ").trim();
        if (snippet.length() > MAX_SNIPPET_LENGTH) {
            snippet = snippet.substring(0, MAX_SNIPPET_LENGTH);
        }
        return snippet;
    }

    public static final class TextHealthReport {
        private final boolean suspicious;
        private final boolean hasSuspiciousEncoding;
        private final List<String> issues;
        private final String snippet;

        public TextHealthReport(boolean suspicious,
                                boolean hasSuspiciousEncoding,
                                List<String> issues,
                                String snippet) {
            this.suspicious = suspicious;
            this.hasSuspiciousEncoding = hasSuspiciousEncoding;
            this.issues = issues != null ? List.copyOf(issues) : List.of();
            this.snippet = snippet != null ? snippet : "";
        }

        public boolean suspicious() { return suspicious; }
        public boolean hasSuspiciousEncoding() { return hasSuspiciousEncoding; }
        public List<String> issues() { return issues; }
        public String snippet() { return snippet; }

        public String summary() {
            if (!suspicious) {
                return "clean";
            }
            StringBuilder sb = new StringBuilder(String.join(",", issues));
            if (!snippet.isBlank()) {
                sb.append(" snippet=").append(snippet);
            }
            return sb.toString();
        }
    }

    private static final class MatchRegion {
        private final boolean found;
        private final int start;
        private final int end;

        private MatchRegion(boolean found, int start, int end) {
            this.found = found;
            this.start = start;
            this.end = end;
        }

        private static MatchRegion notFound() {
            return new MatchRegion(false, -1, -1);
        }
    }
}
