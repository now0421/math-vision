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
 * Google Gemini AI client using the Generative Language REST API.
 *
 * Reads configuration from environment variables:
 *   GEMINI_API_KEY — API key
 *   GEMINI_MODEL — model name (default: gemini-2.0-flash)
 */
public class GeminiAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final HttpClient http;

    public GeminiAiClient() {
        this.apiKey = requireEnv("GEMINI_API_KEY");
        this.model = envOrDefault("GEMINI_MODEL", "gemini-2.0-flash");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String chat(String userMessage, String systemPrompt) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + apiKey;

            ObjectNode body = mapper.createObjectNode();

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sysInstruction = body.putObject("system_instruction");
                ArrayNode parts = sysInstruction.putArray("parts");
                parts.addObject().put("text", systemPrompt);
            }

            ArrayNode contents = body.putArray("contents");
            ObjectNode userContent = contents.addObject();
            userContent.put("role", "user");
            ArrayNode parts = userContent.putArray("parts");
            parts.addObject().put("text", userMessage);

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofMinutes(5))
                    .build();

            log.debug("Gemini request: model={}", model);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Gemini API returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode candidates = root.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("Gemini API returned no candidates");
            }
            return candidates.get(0).path("content").path("parts").get(0).path("text").asText("");

        } catch (Exception e) {
            log.error("Gemini chat failed: {}", e.getMessage(), e);
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public JsonNode chatWithToolsRaw(String userMessage, String systemPrompt, String toolsJson) {
        // Gemini doesn't use OpenAI-format tool calling; fall back to plain chat
        // and wrap the result in a synthetic choices structure for consistency
        String text = chat(userMessage, systemPrompt);
        ObjectNode fake = mapper.createObjectNode();
        ArrayNode choices = fake.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode message = choice.putObject("message");
        message.put("content", text);
        return fake;
    }

    @Override
    public String providerName() { return "gemini"; }

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
