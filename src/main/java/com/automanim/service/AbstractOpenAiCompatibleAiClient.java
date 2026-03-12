package com.automanim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared base for providers exposing an OpenAI-compatible chat completions API.
 */
public abstract class AbstractOpenAiCompatibleAiClient implements AiClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EMPTY_RESPONSE_RETRIES = 2;

    private final Logger log;
    private final String providerName;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final boolean reasoningContentFallback;
    private final HttpClient http;

    protected AbstractOpenAiCompatibleAiClient(
            Logger log,
            String providerName,
            String apiKeyEnv,
            String baseUrlEnv,
            String defaultBaseUrl,
            String modelEnv,
            String defaultModel,
            double temperature,
            int maxTokens,
            boolean reasoningContentFallback
    ) {
        this.log = log;
        this.providerName = providerName;
        this.apiKey = requireEnv(apiKeyEnv);
        this.baseUrl = envOrDefault(baseUrlEnv, defaultBaseUrl);
        this.model = envOrDefault(modelEnv, defaultModel);
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningContentFallback = reasoningContentFallback;
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
            log.error("{} chat failed: {}", providerName, e.getMessage(), e);
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public JsonNode chatWithToolsRaw(String userMessage, String systemPrompt, String toolsJson) {
        try {
            ArrayNode tools = toolsJson != null
                    ? (ArrayNode) MAPPER.readTree(toolsJson)
                    : null;
            ObjectNode body = buildRequestBody(userMessage, systemPrompt, tools);
            return sendRawRequest(body);
        } catch (Exception e) {
            log.error("{} chat (with tools) failed: {}", providerName, e.getMessage(), e);
            throw new RuntimeException("AI chat with tools failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String chatWithTools(String userMessage, String systemPrompt, String toolsJson) {
        try {
            ArrayNode tools = toolsJson != null
                    ? (ArrayNode) MAPPER.readTree(toolsJson)
                    : null;
            ObjectNode body = buildRequestBody(userMessage, systemPrompt, tools);
            return sendRequestWithRetry(body);
        } catch (Exception e) {
            log.error("{} chat (with tools) failed: {}", providerName, e.getMessage(), e);
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return providerName;
    }

    private ObjectNode buildRequestBody(String userMessage, String systemPrompt, ArrayNode tools) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

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
        String jsonBody = MAPPER.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(5))
                .build();

        log.debug("{} request: model={}", providerName, model);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(providerName + " API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return MAPPER.readTree(response.body());
    }

    private String sendRequestWithRetry(ObjectNode body) throws Exception {
        for (int attempt = 0; attempt <= EMPTY_RESPONSE_RETRIES; attempt++) {
            JsonNode root = sendRawRequest(body);
            String content = extractTextContent(root);

            if (content != null && !content.isBlank()) {
                return content;
            }

            if (reasoningContentFallback) {
                String reasoningContent = extractReasoningContent(root);
                if (reasoningContent != null && !reasoningContent.isBlank()) {
                    log.info("Using reasoning_content as fallback for {}", providerName);
                    return reasoningContent;
                }
            }

            if (attempt < EMPTY_RESPONSE_RETRIES) {
                log.warn("Empty response from {} (attempt {}/{}), retrying...",
                        providerName, attempt + 1, EMPTY_RESPONSE_RETRIES + 1);
            }
        }

        throw new RuntimeException(providerName + " API returned empty content after "
                + (EMPTY_RESPONSE_RETRIES + 1) + " attempts");
    }

    protected static String extractTextContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            return null;
        }
        JsonNode content = message.get("content");
        return (content != null && !content.isNull()) ? content.asText("") : null;
    }

    protected static String extractReasoningContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            return null;
        }
        JsonNode reasoning = message.get("reasoning_content");
        return (reasoning != null && !reasoning.isNull()) ? reasoning.asText("") : null;
    }

    protected static String requireEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return val;
    }

    protected static String envOrDefault(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }
}
