package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.NodeConversationContext;
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
    private static final Logger traceLog = LoggerFactory.getLogger("com.mathvision.ai.trace");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_LOG_CHARS = 12000;

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

            return sendGenerateContentAsync(body);
        } catch (Exception e) {
            log.error("Gemini chat failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat failed: " + e.getMessage(), e
            ));
        }
    }

    @Override
    public CompletableFuture<String> chatAsync(NodeConversationContext context) {
        try {
            context.trimToFitBudget();

            ObjectNode body = mapper.createObjectNode();

            String systemContent = context.getSystemContent();
            if (systemContent != null && !systemContent.isBlank()) {
                ObjectNode sysInstruction = body.putObject("system_instruction");
                ArrayNode parts = sysInstruction.putArray("parts");
                parts.addObject().put("text", systemContent);
            }

            body.set("contents", context.buildGeminiContents());

            return sendGenerateContentAsync(body);
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
    public CompletableFuture<JsonNode> chatWithToolsRawAsync(
            NodeConversationContext context, String toolsJson) {
        return chatAsync(context).thenApply(this::wrapTextResponse);
    }

    @Override
    public CompletableFuture<String> chatAsync(
            java.util.List<NodeConversationContext.Message> snapshot) {
        try {
            ObjectNode body = mapper.createObjectNode();

            String systemContent = NodeConversationContext.getSystemContent(snapshot);
            if (systemContent != null && !systemContent.isBlank()) {
                ObjectNode sysInstruction = body.putObject("system_instruction");
                ArrayNode parts = sysInstruction.putArray("parts");
                parts.addObject().put("text", systemContent);
            }

            body.set("contents", NodeConversationContext.buildGeminiContents(snapshot));

            return sendGenerateContentAsync(body);
        } catch (Exception e) {
            log.error("Gemini chat failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat failed: " + e.getMessage(), e
            ));
        }
    }

    @Override
    public CompletableFuture<JsonNode> chatWithToolsRawAsync(
            java.util.List<NodeConversationContext.Message> snapshot, String toolsJson) {
        return chatAsync(snapshot).thenApply(this::wrapTextResponse);
    }

    @Override
    public String providerName() { return modelConfig.resolveProvider() + ":" + modelConfig.getModel(); }

    private CompletableFuture<String> sendGenerateContentAsync(ObjectNode body) throws Exception {
        String url = modelConfig.resolveBaseUrl().replaceAll("/+$", "")
                + "/" + modelConfig.getModel() + ":generateContent?key=" + apiKey;

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
        traceLog.debug("Gemini request body: model={}, url={}\n{}",
                modelConfig.getModel(), url, abbreviateForLog(body.toPrettyString()));
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    traceLog.debug("Gemini raw response: http={}, body=\n{}",
                            response.statusCode(), abbreviateForLog(response.body()));
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
    }

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

    private String abbreviateForLog(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_LOG_CHARS) {
            return text;
        }
        return text.substring(0, MAX_LOG_CHARS)
                + "\n... [truncated " + (text.length() - MAX_LOG_CHARS) + " chars]";
    }
}
