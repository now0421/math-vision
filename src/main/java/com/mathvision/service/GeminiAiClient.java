package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
        return sendGenerateContentAsync(body, url, jsonBody, 0, 0,
                AiRetryPolicy.initialTimeoutSeconds(modelConfig));
    }

    private CompletableFuture<String> sendGenerateContentAsync(ObjectNode body,
                                                               String url,
                                                               String jsonBody,
                                                               int transientAttempt,
                                                               int timeoutAttempt,
                                                               int timeoutSeconds) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        AiTraceLogger.logRequest("Gemini", modelConfig.getModel(), url, body.toPrettyString());
        log.debug("Gemini request timeout: {}s", timeoutSeconds);
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<String>>handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                        if (AiRetryPolicy.isTimeoutFailure(cause)) {
                            if (timeoutAttempt < AiRetryPolicy.timeoutRetryAttempts(modelConfig)) {
                                int nextTimeoutSeconds = AiRetryPolicy.nextTimeoutSeconds(modelConfig, timeoutSeconds);
                                AiRetryPolicy.logTimeoutRetry(log, providerName(), timeoutSeconds,
                                        timeoutAttempt, AiRetryPolicy.timeoutRetryAttempts(modelConfig), nextTimeoutSeconds);
                                return sendGenerateContentAsync(body, url, jsonBody,
                                        transientAttempt, timeoutAttempt + 1, nextTimeoutSeconds);
                            }
                            AiRetryPolicy.logTimeoutExhausted(log, providerName(), timeoutSeconds);
                            return CompletableFuture.failedFuture(cause);
                        }
                        if (transientAttempt < AiRetryPolicy.transientFailureRetries(modelConfig)
                                && AiRetryPolicy.isRetryableTransportFailure(cause)) {
                            return scheduleRetry(body, url, jsonBody, transientAttempt, timeoutAttempt,
                                    timeoutSeconds, cause.getMessage());
                        }
                        return CompletableFuture.failedFuture(cause);
                    }

                    AiTraceLogger.logResponse("Gemini", response);
                    if (response.statusCode() != 200) {
                        String message = "Gemini API returned HTTP " + response.statusCode()
                                + ": " + response.body();
                        if (transientAttempt < AiRetryPolicy.transientFailureRetries(modelConfig)
                                && AiRetryPolicy.isRetryableStatusCode(response.statusCode())) {
                            return scheduleRetry(body, url, jsonBody, transientAttempt, timeoutAttempt,
                                    timeoutSeconds, message);
                        }
                        return CompletableFuture.failedFuture(new RuntimeException(message));
                    }

                    try {
                        JsonNode root = mapper.readTree(response.body());
                        JsonNode candidates = root.get("candidates");
                        if (candidates == null || candidates.isEmpty()) {
                            return CompletableFuture.failedFuture(
                                    new RuntimeException("Gemini API returned no candidates"));
                        }
                        String text = candidates.get(0).path("content").path("parts")
                                .get(0).path("text").asText("");
                        AiTraceLogger.logTextSample("Gemini", "content_text", text);
                        return CompletableFuture.completedFuture(text);
                    } catch (Exception e) {
                        return CompletableFuture.failedFuture(e);
                    }
                })
                .thenCompose(Function.identity())
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

    private CompletableFuture<String> scheduleRetry(ObjectNode body,
                                                    String url,
                                                    String jsonBody,
                                                    int transientAttempt,
                                                    int timeoutAttempt,
                                                    int timeoutSeconds,
                                                    String reason) {
        long delayMillis = AiRetryPolicy.retryDelayMillis(transientAttempt);
        log.warn("Transient failure from {} (attempt {}/{}), retrying in {} ms: {}",
                providerName(),
                transientAttempt + 1,
                AiRetryPolicy.transientFailureRetries(modelConfig) + 1,
                delayMillis,
                reason);
        return CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> sendGenerateContentAsync(body, url, jsonBody,
                        transientAttempt + 1, timeoutAttempt, timeoutSeconds));
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

    @Override
    public CompletableFuture<String> chatMultimodalAsync(List<AiMessage> messages) {
        try {
            ObjectNode body = buildMultimodalBody(messages);
            return sendGenerateContentAsync(body);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException(
                    "Gemini multimodal chat failed: " + e.getMessage(), e));
        }
    }

    @Override
    public CompletableFuture<JsonNode> chatMultimodalWithToolsRawAsync(
            List<AiMessage> messages, String toolsJson) {
        return chatMultimodalAsync(messages).thenApply(this::wrapTextResponse);
    }

    private ObjectNode buildMultimodalBody(List<AiMessage> messages) {
        ObjectNode body = mapper.createObjectNode();

        for (AiMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                ObjectNode sysInstruction = body.putObject("system_instruction");
                ArrayNode parts = sysInstruction.putArray("parts");
                for (AiContentPart part : msg.getParts()) {
                    if ("text".equals(part.getType())) {
                        parts.addObject().put("text", part.getText());
                    }
                }
                break;
            }
        }

        ArrayNode contents = body.putArray("contents");
        for (AiMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                continue;
            }
            ObjectNode entry = contents.addObject();
            entry.put("role", "assistant".equals(msg.getRole()) ? "model" : msg.getRole());
            ArrayNode parts = entry.putArray("parts");
            for (AiContentPart part : msg.getParts()) {
                if ("text".equals(part.getType())) {
                    parts.addObject().put("text", part.getText());
                } else if ("image".equals(part.getType())) {
                    ObjectNode inlineData = parts.addObject().putObject("inline_data");
                    inlineData.put("mime_type", part.getMimeType());
                    inlineData.put("data", part.getDataBase64());
                }
            }
        }

        return body;
    }
}
