package com.mathvision.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jackson JSON utility methods shared across services.
 */
public final class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*?]", Pattern.DOTALL);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "(?is)(?:^|\\R)```+\\s*([a-z0-9_+.-]*)\\s*\\R?([\\s\\S]*?)\\R?```+"
    );
    private static final Pattern FENCE_LINE_PATTERN = Pattern.compile(
            "^\\s*(```+|~~~+)(?:\\s*([A-Za-z0-9_+.-]+))?\\s*$"
    );
    private static final Pattern CLOSING_FENCE_LINE_PATTERN = Pattern.compile("^\\s*(```+|~~~+)\\s*$");
    private static final Pattern STANDALONE_LANGUAGE_LABEL_PATTERN =
            Pattern.compile("^(?i:python|py)\\s*$");
    private static final Set<String> JSON_KEYWORDS = Set.of("true", "false", "null");

    private JsonUtils() {}

    public static ObjectMapper mapper() { return MAPPER; }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    public static String toPrettyJson(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed: " + e.getMessage(), e);
        }
    }

    public static JsonNode parseTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON parse failed: " + e.getMessage(), e);
        }
    }

    public static JsonNode parseTreeBestEffort(String jsonLike) {
        if (jsonLike == null || jsonLike.isBlank()) {
            return null;
        }

        String trimmed = jsonLike.trim();
        try {
            return MAPPER.readTree(trimmed);
        } catch (JsonProcessingException ignored) {
        }

        if (trimmed.startsWith("{")) {
            String repaired = repairJsonLikeObject(trimmed);
            try {
                return MAPPER.readTree(repaired);
            } catch (JsonProcessingException ignored) {
            }
        }

        String extracted = extractJsonObject(trimmed);
        if (extracted == null || extracted.isBlank()) {
            return null;
        }

        try {
            return MAPPER.readTree(extracted);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String extractJsonArray(String text) {
        if (text == null || text.isBlank()) return "[]";

        // Layer 1: Direct parse
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            try {
                MAPPER.readTree(trimmed);
                return trimmed;
            } catch (JsonProcessingException ignored) {}
        }

        // Layer 2: Extract from code blocks
        String fromBlock = extractFromCodeBlock(text, "[");
        if (fromBlock != null) {
            try {
                MAPPER.readTree(fromBlock);
                return fromBlock;
            } catch (JsonProcessingException ignored) {}
        }

        // Layer 3: Regex extraction
        Matcher m = JSON_ARRAY_PATTERN.matcher(text);
        if (m.find()) {
            String candidate = m.group(0);
            try {
                MAPPER.readTree(candidate);
                return candidate;
            } catch (JsonProcessingException ignored) {}
        }

        // Layer 4: Fallback - find [ and ]
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            String candidate = text.substring(start, end + 1);
            try {
                MAPPER.readTree(candidate);
                return candidate;
            } catch (JsonProcessingException ignored) {}
        }

        log.warn("Could not extract JSON array from response, returning empty");
        return "[]";
    }

    public static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) return null;

        // Layer 1: Direct parse
        String trimmed = text.trim();
        String direct = validateJsonObjectCandidate(trimmed);
        if (direct != null) {
            return direct;
        }

        // Layer 1b: Direct repair parse for object-looking payloads
        if (trimmed.startsWith("{")) {
            RepairSummary repair = repairJsonLikeObjectWithSummary(trimmed);
            String repairedDirect = validateJsonObjectCandidate(repair.repairedText);
            if (repairedDirect != null) {
                log.warn("Recovered JSON object via direct repair: {}", repair.summary());
                return repairedDirect;
            }
        }

        // Layer 2: Code blocks
        String fromBlock = extractFromCodeBlock(text, "{");
        String validatedBlock = validateJsonObjectCandidate(fromBlock);
        if (validatedBlock != null) {
            return validatedBlock;
        }

        // Layer 2b: Repaired code block
        if (fromBlock != null && !fromBlock.isBlank()) {
            RepairSummary repair = repairJsonLikeObjectWithSummary(fromBlock);
            String repairedBlock = validateJsonObjectCandidate(repair.repairedText);
            if (repairedBlock != null) {
                log.warn("Recovered JSON object via repaired code block: {}", repair.summary());
                return repairedBlock;
            }
        }

        // Layer 3: scan for the first balanced, parseable JSON object.
        String scanned = scanForJsonObject(text);
        if (scanned != null) {
            return scanned;
        }

        // Layer 4: scan for balanced object, then repair and validate.
        String scannedWithRepair = scanForJsonObjectWithRepair(text);
        if (scannedWithRepair != null) {
            return scannedWithRepair;
        }

        log.warn("Could not extract JSON object from response");
        return null;
    }

    public static String extractCodeBlock(String text) {
        if (text == null || text.isBlank()) return text != null ? text.trim() : "";

        String trimmed = stripBom(text).trim();
        String bestBlock = "";
        int bestScore = -1;

        Matcher m = CODE_BLOCK_PATTERN.matcher(trimmed);
        while (m.find()) {
            String language = m.group(1);
            String block = stripMarkdownCodeFences(m.group(2));
            int score = scoreCodeBlock(language, block);

            if (score > bestScore) {
                bestScore = score;
                bestBlock = block;
            }
        }

        if (bestScore > 0) {
            return bestBlock;
        }

        String stripped = stripMarkdownCodeFences(trimmed);
        if (looksLikeCode(stripped)) {
            return stripped;
        }

        // Fallback: If it already looks like raw code (no usable fence), return as-is
        if (looksLikeCode(trimmed)) {
            return trimmed;
        }

        return stripped;
    }

    public static JsonNode extractToolCallPayload(JsonNode response) {
        JsonNode functionNode = extractFirstToolFunction(response);
        if (functionNode == null) return null;

        JsonNode arguments = functionNode.get("arguments");
        if (arguments == null || arguments.isNull()) return null;

        if (arguments.isObject() || arguments.isArray()) {
            return arguments;
        }

        String argsStr = arguments.asText("");
        if (argsStr.isBlank()) return null;

        JsonNode parsed = parseTreeBestEffort(argsStr);
        if (parsed != null) {
            return parsed;
        }

        log.warn("Failed to parse tool call arguments as JSON");
        return null;
    }

    public static String extractToolCallName(JsonNode response) {
        JsonNode functionNode = extractFirstToolFunction(response);
        if (functionNode == null) {
            return "";
        }

        JsonNode name = functionNode.get("name");
        return name != null && !name.isNull() ? name.asText("") : "";
    }

    public static String buildToolCallTranscript(JsonNode response) {
        String toolName = extractToolCallName(response);
        JsonNode payload = extractToolCallPayload(response);
        String textContent = extractTextFromResponse(response);

        if ((toolName == null || toolName.isBlank())
                && payload == null
                && (textContent == null || textContent.isBlank())) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[tool_call]").append("\n");

        if (toolName != null && !toolName.isBlank()) {
            sb.append("name: ").append(toolName).append("\n");
        }

        if (payload != null) {
            sb.append("arguments:").append("\n");
            sb.append(payload.toPrettyString()).append("\n");
        }

        if (textContent != null && !textContent.isBlank()) {
            sb.append("assistant_text:").append("\n");
            sb.append(textContent.trim()).append("\n");
        }

        sb.append("[/tool_call]");
        return sb.toString();
    }

    public static String extractTextFromResponse(JsonNode response) {
        return extractMessageFieldText(extractFirstMessage(response), "content");
    }

    public static String extractReasoningTextFromResponse(JsonNode response) {
        return extractMessageFieldText(extractFirstMessage(response), "reasoning_content");
    }

    public static String extractBestEffortTextFromResponse(JsonNode response) {
        String content = extractTextFromResponse(response);
        if (content != null && !content.isBlank()) {
            return content;
        }
        return extractReasoningTextFromResponse(response);
    }

    // ---- Private helpers ----

    private static JsonNode extractFirstMessage(JsonNode response) {
        if (response == null) return null;

        JsonNode choices = response.get("choices");
        if (choices == null || choices.isEmpty()) return null;

        return choices.get(0).get("message");
    }

    private static JsonNode extractFirstToolFunction(JsonNode response) {
        JsonNode message = extractFirstMessage(response);
        if (message == null) return null;

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls == null || toolCalls.isEmpty()) return null;

        return toolCalls.get(0).get("function");
    }

    private static String extractMessageFieldText(JsonNode message, String fieldName) {
        if (message == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }

        return extractTextValue(message.get(fieldName));
    }

    private static String extractTextValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }

        if (node.isTextual()) {
            return node.asText("");
        }

        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String value = extractTextValue(item);
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(value.trim());
            }
            return sb.toString();
        }

        if (node.isObject()) {
            String text = firstNonBlankText(
                    extractTextValue(node.get("text")),
                    extractTextValue(node.get("content")),
                    extractTextValue(node.get("value")),
                    extractTextValue(node.get("parts"))
            );
            return text != null ? text : "";
        }

        return "";
    }

    private static String firstNonBlankText(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String validateJsonObjectCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonNode parsed = MAPPER.readTree(trimmed);
            return parsed != null && parsed.isObject() ? trimmed : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String scanForJsonObject(String text) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (start < 0) {
                if (ch == '{') {
                    start = i;
                    depth = 1;
                    inString = false;
                    escaping = false;
                }
                continue;
            }

            if (escaping) {
                escaping = false;
                continue;
            }

            if (ch == '\\' && inString) {
                escaping = true;
                continue;
            }

            if (ch == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    String validated = validateJsonObjectCandidate(candidate);
                    if (validated != null) {
                        return validated;
                    }
                    start = -1;
                }
            }
        }

        return null;
    }

    private static String scanForJsonObjectWithRepair(String text) {
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (start < 0) {
                if (ch == '{') {
                    start = i;
                    depth = 1;
                    inString = false;
                    escaping = false;
                }
                continue;
            }

            if (escaping) {
                escaping = false;
                continue;
            }

            if (ch == '\\' && inString) {
                escaping = true;
                continue;
            }

            if (ch == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    RepairSummary repair = repairJsonLikeObjectWithSummary(candidate);
                    String validated = validateJsonObjectCandidate(repair.repairedText);
                    if (validated != null) {
                        log.warn("Recovered JSON object via scan repair: {}", repair.summary());
                        return validated;
                    }
                    start = -1;
                }
            }
        }

        return null;
    }

    public static String repairJsonLikeObject(String input) {
        return repairJsonLikeObjectWithSummary(input).repairedText;
    }

    private static RepairSummary repairJsonLikeObjectWithSummary(String input) {
        if (input == null) {
            return new RepairSummary("", 0, 0, 0, 0);
        }

        String normalizedQuotes = normalizeSmartQuotes(input);
        int smartQuoteChanges = countDifferences(input, normalizedQuotes);

        SingleQuoteNormalizationResult singleQuoteResult = normalizeSingleQuotedStrings(normalizedQuotes);
        BareIdentifierQuotingResult bareIdentifierResult = quoteBareIdentifierValues(singleQuoteResult.text);
        TrailingCommaResult trailingCommaResult = removeTrailingCommas(bareIdentifierResult.text);

        return new RepairSummary(
                trailingCommaResult.text,
                smartQuoteChanges,
                singleQuoteResult.replacedSegments,
                bareIdentifierResult.quotedValues,
                trailingCommaResult.removedCommas
        );
    }

    private static String normalizeSmartQuotes(String input) {
        return input
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'');
    }

    private static SingleQuoteNormalizationResult normalizeSingleQuotedStrings(String input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaping = false;
        int replacements = 0;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                out.append(ch);
                escaping = false;
                continue;
            }

            if (ch == '\\') {
                out.append(ch);
                escaping = true;
                continue;
            }

            if (!inSingle && ch == '"') {
                inDouble = !inDouble;
                out.append(ch);
                continue;
            }

            if (!inDouble && ch == '\'') {
                inSingle = !inSingle;
                out.append('"');
                replacements++;
                continue;
            }

            out.append(ch);
        }

        return new SingleQuoteNormalizationResult(out.toString(), replacements / 2);
    }

    private static BareIdentifierQuotingResult quoteBareIdentifierValues(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        boolean expectingValue = false;
        int quotedValues = 0;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                out.append(ch);
                escaping = false;
                continue;
            }

            if (ch == '\\' && inString) {
                out.append(ch);
                escaping = true;
                continue;
            }

            if (ch == '"') {
                inString = !inString;
                out.append(ch);
                continue;
            }

            if (inString) {
                out.append(ch);
                continue;
            }

            if (ch == ':') {
                expectingValue = true;
                out.append(ch);
                continue;
            }

            if (expectingValue && Character.isWhitespace(ch)) {
                out.append(ch);
                continue;
            }

            if (expectingValue) {
                if (ch == '\"' || ch == '{' || ch == '[') {
                    expectingValue = false;
                    out.append(ch);
                    continue;
                }

                if (ch == '-' || Character.isDigit(ch)) {
                    int start = i;
                    int end = i + 1;
                    while (end < input.length() && !isValueDelimiter(input.charAt(end))) {
                        end++;
                    }
                    String token = input.substring(start, end);
                    if (looksLikeJsonNumber(token)) {
                        out.append(token);
                    } else {
                        out.append('"').append(token).append('"');
                        quotedValues++;
                    }
                    i = end - 1;
                    expectingValue = false;
                    continue;
                }

                if (isIdentifierStart(ch)) {
                    int start = i;
                    int end = i + 1;
                    while (end < input.length() && isIdentifierPart(input.charAt(end))) {
                        end++;
                    }
                    String token = input.substring(start, end);
                    if (JSON_KEYWORDS.contains(token)) {
                        out.append(token);
                    } else {
                        out.append('"').append(token).append('"');
                        quotedValues++;
                    }
                    i = end - 1;
                    expectingValue = false;
                    continue;
                }

                expectingValue = false;
                out.append(ch);
                continue;
            }

            out.append(ch);
        }

        return new BareIdentifierQuotingResult(out.toString(), quotedValues);
    }

    private static TrailingCommaResult removeTrailingCommas(String input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean inString = false;
        boolean escaping = false;
        int removed = 0;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (escaping) {
                out.append(ch);
                escaping = false;
                continue;
            }

            if (ch == '\\' && inString) {
                out.append(ch);
                escaping = true;
                continue;
            }

            if (ch == '"') {
                inString = !inString;
                out.append(ch);
                continue;
            }

            if (!inString && ch == ',') {
                int j = i + 1;
                while (j < input.length() && Character.isWhitespace(input.charAt(j))) {
                    j++;
                }
                if (j < input.length()) {
                    char next = input.charAt(j);
                    if (next == '}' || next == ']') {
                        removed++;
                        continue;
                    }
                }
            }

            out.append(ch);
        }

        return new TrailingCommaResult(out.toString(), removed);
    }

    private static boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_' || ch == '$';
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '-';
    }

    private static boolean isValueDelimiter(char ch) {
        return Character.isWhitespace(ch) || ch == ',' || ch == '}' || ch == ']';
    }

    private static boolean looksLikeJsonNumber(String token) {
        return token != null
                && token.matches("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    }

    private static int countDifferences(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        int min = Math.min(a.length(), b.length());
        int diff = Math.abs(a.length() - b.length());
        for (int i = 0; i < min; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                diff++;
            }
        }
        return diff;
    }

    private static final class SingleQuoteNormalizationResult {
        private final String text;
        private final int replacedSegments;

        private SingleQuoteNormalizationResult(String text, int replacedSegments) {
            this.text = text;
            this.replacedSegments = replacedSegments;
        }
    }

    private static final class BareIdentifierQuotingResult {
        private final String text;
        private final int quotedValues;

        private BareIdentifierQuotingResult(String text, int quotedValues) {
            this.text = text;
            this.quotedValues = quotedValues;
        }
    }

    private static final class TrailingCommaResult {
        private final String text;
        private final int removedCommas;

        private TrailingCommaResult(String text, int removedCommas) {
            this.text = text;
            this.removedCommas = removedCommas;
        }
    }

    private static final class RepairSummary {
        private final String repairedText;
        private final int smartQuoteChanges;
        private final int normalizedSingleQuotedStrings;
        private final int quotedBareIdentifiers;
        private final int removedTrailingCommas;

        private RepairSummary(String repairedText,
                              int smartQuoteChanges,
                              int normalizedSingleQuotedStrings,
                              int quotedBareIdentifiers,
                              int removedTrailingCommas) {
            this.repairedText = repairedText;
            this.smartQuoteChanges = smartQuoteChanges;
            this.normalizedSingleQuotedStrings = normalizedSingleQuotedStrings;
            this.quotedBareIdentifiers = quotedBareIdentifiers;
            this.removedTrailingCommas = removedTrailingCommas;
        }

        private String summary() {
            return String.format(
                    "smart_quote_changes=%d, single_quoted_strings=%d, quoted_bare_identifiers=%d, removed_trailing_commas=%d",
                    smartQuoteChanges,
                    normalizedSingleQuotedStrings,
                    quotedBareIdentifiers,
                    removedTrailingCommas
            );
        }
    }

    private static String extractFromCodeBlock(String text, String expectedStart) {
        if (!text.contains("```")) return null;

        // Try ```json blocks first
        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = text.indexOf('\n', jsonStart) + 1;
            int end = text.indexOf("```", contentStart);
            if (end > contentStart) {
                String content = text.substring(contentStart, end).trim();
                if (content.startsWith(expectedStart)) return content;
            }
        }

        // Try generic ``` blocks
        String[] segments = text.split("```");
        for (int i = 1; i < segments.length; i += 2) {
            String segment = segments[i].trim();
            if (segment.startsWith("json")) segment = segment.substring(4).trim();
            if (segment.startsWith(expectedStart)) return segment;
        }

        return null;
    }

    private static int scoreCodeBlock(String language, String block) {
        if (block == null || block.isBlank()) {
            return -1;
        }

        int score = block.length();
        String normalizedLanguage = language != null ? language.trim().toLowerCase() : "";

        if ("python".equals(normalizedLanguage) || "py".equals(normalizedLanguage)) {
            score += 2000;
        }

        if (block.contains("class ") && block.contains("(Scene):")) {
            score += 10000;
        } else if (block.contains("class ") && block.contains("Scene")) {
            score += 5000;
        }

        if (block.contains("from manim import")) {
            score += 1000;
        }

        if (block.contains("def construct(")) {
            score += 500;
        }

        return score;
    }

    private static boolean looksLikeCode(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String trimmed = text.trim();
        return trimmed.contains("from manim import")
                || trimmed.contains("import manim")
                || trimmed.contains("def construct(")
                || (trimmed.contains("class ") && trimmed.contains("Scene"));
    }

    private static String stripMarkdownCodeFences(String text) {
        String current = stripBom(text).trim();
        for (int i = 0; i < 3; i++) {
            String updated = stripSingleOuterFence(current);
            if (updated.equals(current)) {
                break;
            }
            current = updated;
        }
        return current;
    }

    private static String stripSingleOuterFence(String text) {
        if (text == null || text.isBlank()) {
            return text != null ? text.trim() : "";
        }

        String[] lines = text.trim().split("\\R", -1);
        int first = firstNonBlankLine(lines);
        if (first < 0) {
            return text.trim();
        }

        Matcher openingFence = FENCE_LINE_PATTERN.matcher(lines[first]);
        if (!openingFence.matches()) {
            return text.trim();
        }

        int last = lastNonBlankLine(lines);
        int contentStart = first + 1;
        int contentEnd = last;

        if (last > first && CLOSING_FENCE_LINE_PATTERN.matcher(lines[last]).matches()) {
            contentEnd = last - 1;
        }

        if (contentStart <= contentEnd
                && STANDALONE_LANGUAGE_LABEL_PATTERN.matcher(lines[contentStart].trim()).matches()) {
            String withoutLanguageLabel = joinLines(lines, contentStart + 1, contentEnd).trim();
            if (looksLikeCode(withoutLanguageLabel)) {
                contentStart++;
            }
        }

        return joinLines(lines, contentStart, contentEnd).trim();
    }

    private static int firstNonBlankLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static int lastNonBlankLine(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static String joinLines(String[] lines, int startInclusive, int endInclusive) {
        if (lines == null || startInclusive > endInclusive || startInclusive >= lines.length || endInclusive < 0) {
            return "";
        }

        int safeStart = Math.max(0, startInclusive);
        int safeEnd = Math.min(lines.length - 1, endInclusive);
        StringBuilder sb = new StringBuilder();
        for (int i = safeStart; i <= safeEnd; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String stripBom(String text) {
        if (text == null || text.isEmpty()) {
            return text != null ? text : "";
        }
        return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }
}
