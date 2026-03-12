package com.automanim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Kimi (Moonshot AI) client using the OpenAI-compatible chat completion API.
 *
 * Reads configuration from environment variables:
 *   MOONSHOT_API_KEY — API key
 *   MOONSHOT_BASE_URL — base URL (default: https://api.moonshot.cn/v1)
 *   KIMI_K2_MODEL — model name (default: kimi-k2-0711-preview)
 */
public class KimiAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(KimiAiClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int EMPTY_RESPONSE_RETRIES = 2;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient http;

    public KimiAiClient() {
        this.apiKey = requireEnv("MOONSHOT_API_KEY");
        this.baseUrl = envOrDefault("MOONSHOT_BASE_URL", "https://api.moonshot.cn/v1");
        this.model = envOrDefault("KIMI_K2_MODEL", "kimi-k2-0711-preview");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String chat(String userMessage, String systemPrompt) {
        try {
            ObjectNode body = buildRequestBody(userMessage, systemPrompt, null);
            return sendRequestWithRetry(body);
        } catch (Exception e) {
            log.error("Kimi chat failed: {}", e.getMessage(), e);
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public JsonNode chatWithToolsRaw(String userMessage, String systemPrompt, String toolsJson) {
        try {
            ArrayNode tools = toolsJson != null
                    ? (ArrayNode) mapper.readTree(toolsJson)
                    : null;
            ObjectNode body = buildRequestBody(userMessage, systemPrompt, tools);
            return sendRawRequest(body);
        } catch (Exception e) {
            log.error("Kimi chat (with tools) failed: {}", e.getMessage(), e);
            throw new RuntimeException("AI chat with tools failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String chatWithTools(String userMessage, String systemPrompt, String toolsJson) {
        try {
            ArrayNode tools = toolsJson != null
                    ? (ArrayNode) mapper.readTree(toolsJson)
                    : null;
            ObjectNode body = buildRequestBody(userMessage, systemPrompt, tools);
            return sendRequestWithRetry(body);
        } catch (Exception e) {
            log.error("Kimi chat (with tools) failed: {}", e.getMessage(), e);
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() { return "kimi"; }

    // ---- Internal ----

    private ObjectNode buildRequestBody(String userMessage, String systemPrompt, ArrayNode tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.6);
        body.put("max_tokens", 8192);

        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
        }
        return body;
    }

    private JsonNode sendRawRequest(ObjectNode body) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String jsonBody = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(5))
                .build();

        log.debug("Kimi request: model={}", model);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Kimi API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return mapper.readTree(response.body());
    }

    private String sendRequestWithRetry(ObjectNode body) throws Exception {
        for (int attempt = 0; attempt <= EMPTY_RESPONSE_RETRIES; attempt++) {
            JsonNode root = sendRawRequest(body);
            String content = extractTextContent(root);

            if (content != null && !content.isBlank()) {
                return content;
            }

                // Attempt reasoning_content extraction (Kimi thinking model quirk)
            String reasoningContent = extractReasoningContent(root);
            if (reasoningContent != null && !reasoningContent.isBlank()) {
                log.info("Using reasoning_content as fallback (content was empty)");
                return reasoningContent;
            }

            if (attempt < EMPTY_RESPONSE_RETRIES) {
                log.warn("Empty response from Kimi (attempt {}/{}), retrying...",
                        attempt + 1, EMPTY_RESPONSE_RETRIES + 1);
            }
        }

        throw new RuntimeException("Kimi API returned empty content after "
                + (EMPTY_RESPONSE_RETRIES + 1) + " attempts");
    }

    static String extractTextContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        JsonNode message = choices.get(0).get("message");
        if (message == null) return null;
        JsonNode content = message.get("content");
        return (content != null && !content.isNull()) ? content.asText("") : null;
    }

    static String extractReasoningContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        JsonNode message = choices.get(0).get("message");
        if (message == null) return null;
        JsonNode reasoning = message.get("reasoning_content");
        return (reasoning != null && !reasoning.isNull()) ? reasoning.asText("") : null;
    }

    private static String requireEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return val;
    }

    private static String envOrDefault(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
