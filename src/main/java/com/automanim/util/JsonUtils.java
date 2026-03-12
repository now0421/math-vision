package com.automanim.util;

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
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

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
        if (text == null || text.isBlank()) return "{}";

        // Layer 1: Direct parse
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            try {
                MAPPER.readTree(trimmed);
                return trimmed;
            } catch (JsonProcessingException ignored) {}
        }

        // Layer 2: Code blocks
        String fromBlock = extractFromCodeBlock(text, "{");
        if (fromBlock != null) {
            try {
                MAPPER.readTree(fromBlock);
                return fromBlock;
            } catch (JsonProcessingException ignored) {}
        }

        // Layer 3: Find { and }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String candidate = text.substring(start, end + 1);
            try {
                MAPPER.readTree(candidate);
                return candidate;
            } catch (JsonProcessingException ignored) {}
        }

        log.warn("Could not extract JSON object from response, returning empty");
        return "{}";
    }

    public static String extractCodeBlock(String text) {
        if (text == null || text.isBlank()) return text != null ? text.trim() : "";

        // Try ```python ... ``` first
        int start = text.indexOf("```python");
        if (start >= 0) {
            start = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", start);
            if (end > start) {
                return text.substring(start, end).trim();
            }
        }
        // Try ``` ... ```
        start = text.indexOf("```");
        if (start >= 0) {
            start = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", start);
            if (end > start) {
                String block = text.substring(start, end).trim();
                // Verify it looks like code
                if (block.contains("from manim import") || block.contains("class ")) {
                    return block;
                }
                return block;
            }
        }

        // If it looks like raw code, return as-is
        if (text.contains("from manim import") || (text.contains("class ") && text.contains("Scene"))) {
            return text.trim();
        }

        return text.trim();
    }

    public static JsonNode extractToolCallPayload(JsonNode response) {
        if (response == null) return null;

        JsonNode choices = response.get("choices");
        if (choices == null || choices.isEmpty()) return null;

        JsonNode message = choices.get(0).get("message");
        if (message == null) return null;

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls == null || toolCalls.isEmpty()) return null;

        JsonNode functionNode = toolCalls.get(0).get("function");
        if (functionNode == null) return null;

        JsonNode arguments = functionNode.get("arguments");
        if (arguments == null || arguments.isNull()) return null;

        String argsStr = arguments.asText("");
        if (argsStr.isBlank()) return null;

        try {
            return MAPPER.readTree(argsStr);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool call arguments: {}", e.getMessage());
            return null;
        }
    }

    public static String extractTextFromResponse(JsonNode response) {
        if (response == null) return "";

        JsonNode choices = response.get("choices");
        if (choices == null || choices.isEmpty()) return "";

        JsonNode message = choices.get(0).get("message");
        if (message == null) return "";

        JsonNode content = message.get("content");
        return (content != null && !content.isNull()) ? content.asText("") : "";
    }

    // ---- Private helpers ----

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
}
