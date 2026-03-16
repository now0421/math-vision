package com.automanim.service;

import com.automanim.config.ModelConfig;
import com.automanim.util.ConcurrencyUtils;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Google Gemini AI client using the Generative Language REST API.
 *
 * Reads configuration from environment variables:
 *   GEMINI_API_KEY - API key
 *   GEMINI_MODEL - model name (default: gemini-2.0-flash)
 */
public class GeminiAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final ModelConfig modelConfig;
    private final HttpClient http;

    public GeminiAiClient(ModelConfig modelConfig) {
        this.apiKey = requireEnv(modelConfig.getApiKeyEnv());
        this.modelConfig = modelConfig;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String chat(String userMessage, String systemPrompt) {
        try {
            return chatAsync(userMessage, systemPrompt).join();
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("AI chat failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public CompletableFuture<String> chatAsync(String userMessage, String systemPrompt) {
        try {
            String url = modelConfig.resolveBaseUrl().replaceAll("/+$", "")
                    + "/" + modelConfig.getModel() + ":generateContent?key=" + apiKey;

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

            ObjectNode generationConfig = body.putObject("generationConfig");
            generationConfig.put("temperature", modelConfig.getTemperature());
            generationConfig.put("maxOutputTokens", modelConfig.getMaxOutputTokens());

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofMinutes(5))
                    .build();

            log.debug("Gemini request: model={}", modelConfig.getModel());
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new CompletionException(new RuntimeException(
                                    "Gemini API returned HTTP " + response.statusCode()
                                            + ": " + response.body()
                            ));
                        }
                        try {
                            JsonNode root = mapper.readTree(response.body());
                            JsonNode candidates = root.get("candidates");
                            if (candidates == null || candidates.isEmpty()) {
                                throw new RuntimeException("Gemini API returned no candidates");
                            }
                            return candidates.get(0).path("content").path("parts")
                                    .get(0).path("text").asText("");
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    })
                    .handle((result, error) -> {
                        if (error == null) {
                            return result;
                        }
                        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                        log.error("Gemini chat failed: {}", cause.getMessage(), cause);
                        throw new CompletionException(new RuntimeException(
                                "AI chat failed: " + cause.getMessage(), cause
                        ));
                    });
        } catch (Exception e) {
            log.error("Gemini chat failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat failed: " + e.getMessage(), e
            ));
        }
    }

    @Override
    public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                             String systemPrompt,
                                                             String toolsJson) {
        return chatAsync(userMessage, systemPrompt).thenApply(this::wrapTextResponse);
    }

    @Override
    public String providerName() { return modelConfig.resolveProvider() + ":" + modelConfig.getModel(); }

    private static String requireEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return val;
    }

    private JsonNode wrapTextResponse(String text) {
        ObjectNode fake = mapper.createObjectNode();
        ArrayNode choices = fake.putArray("choices");
        ObjectNode choice = choices.addObject();
        ObjectNode message = choice.putObject("message");
        message.put("content", text);
        return fake;
    }
}
