package com.mathvision.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // Layer 2: Code blocks
        String fromBlock = extractFromCodeBlock(text, "{");
        String validatedBlock = validateJsonObjectCandidate(fromBlock);
        if (validatedBlock != null) {
            return validatedBlock;
        }

        // Layer 3: scan for the first balanced, parseable JSON object.
        String scanned = scanForJsonObject(text);
        if (scanned != null) {
            return scanned;
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

        try {
            return MAPPER.readTree(argsStr);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool call arguments: {}", e.getMessage());
            return null;
        }
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
