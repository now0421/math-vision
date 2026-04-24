package com.mathvision.service;

import com.mathvision.config.ModelConfig;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Shared base for providers exposing an OpenAI-compatible chat completions API.
 */
public abstract class AbstractOpenAiCompatibleAiClient implements AiClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EMPTY_RESPONSE_RETRIES = 2;
    private static final int TRANSIENT_FAILURE_RETRIES = 2;
    private static final long RETRY_BASE_DELAY_MILLIS = 1_000L;
    private static final long RETRY_MAX_DELAY_MILLIS = 4_000L;

    private final Logger log;
    private final String clientName;
    private final String apiKey;
    private final String baseUrl;
    private final ModelConfig modelConfig;
    private final HttpClient http;

    protected AbstractOpenAiCompatibleAiClient(
            Logger log,
            ModelConfig modelConfig
    ) {
        this.log = log;
        this.modelConfig = modelConfig;
        this.clientName = modelConfig.resolveProvider() + ":" + modelConfig.getModel();
        this.apiKey = requireEnv(modelConfig.getApiKeyEnv());
        this.baseUrl = modelConfig.resolveBaseUrl();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String providerName() {
        return clientName;
    }

    @Override
    public CompletableFuture<String> chatAsync(
            java.util.List<NodeConversationContext.Message> snapshot) {
        try {
            ObjectNode body = buildRequestBody(snapshot, null);
            return sendRequestWithRetryAsync(body).handle((result, error) -> {
                if (error == null) {
                    return result;
                }
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                log.error("{} chat failed: {}", clientName, cause.getMessage(), cause);
                throw new CompletionException(new RuntimeException(
                        "AI chat failed: " + cause.getMessage(), cause
                ));
            });
        } catch (Exception e) {
            log.error("{} chat failed: {}", clientName, e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat failed: " + e.getMessage(), e
            ));
        }
    }

    @Override
    public CompletableFuture<JsonNode> chatWithToolsRawAsync(
            java.util.List<NodeConversationContext.Message> snapshot, String toolsJson) {
        try {
            ArrayNode tools = toolsJson != null
                    ? (ArrayNode) MAPPER.readTree(toolsJson) : null;
            ObjectNode body = buildRequestBody(snapshot, tools);
            return sendRawRequestAsync(body).handle((result, error) -> {
                if (error == null) {
                    return result;
                }
                Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                log.error("{} chat (with tools) failed: {}", clientName, cause.getMessage(), cause);
                throw new CompletionException(new RuntimeException(
                        "AI chat with tools failed: " + cause.getMessage(), cause
                ));
            });
        } catch (Exception e) {
            log.error("{} chat (with tools) failed: {}", clientName, e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat with tools failed: " + e.getMessage(), e
            ));
        }
    }

    private ObjectNode buildRequestBody(
            java.util.List<NodeConversationContext.Message> snapshot, ArrayNode tools) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelConfig.getModel());
        body.put("temperature", modelConfig.getTemperature());
        body.put("max_tokens", modelConfig.getMaxOutputTokens());
        body.set("messages", NodeConversationContext.buildOpenAiMessages(snapshot));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
        }
        return body;
    }

    private CompletableFuture<JsonNode> sendRawRequestAsync(ObjectNode body) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String jsonBody = MAPPER.writeValueAsString(body);
        return sendRawRequestAsync(body, url, jsonBody, 0);
    }

    private CompletableFuture<JsonNode> sendRawRequestAsync(
            ObjectNode body,
            String url,
            String jsonBody,
            int attempt
    ) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(5))
                .build();

        logRequest(body, url, attempt);
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<JsonNode>>handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                        if (attempt < TRANSIENT_FAILURE_RETRIES && isRetryableFailure(cause)) {
                            return scheduleRetry(body, url, jsonBody, attempt, cause.getMessage());
                        }
                        return CompletableFuture.<JsonNode>failedFuture(cause);
                    }

                    logResponse(response);
                    if (response.statusCode() != 200) {
                        String message = clientName + " API returned HTTP " + response.statusCode()
                                + ": " + response.body();
                        if (attempt < TRANSIENT_FAILURE_RETRIES
                                && isRetryableStatusCode(response.statusCode())) {
                            return scheduleRetry(body, url, jsonBody, attempt, message);
                        }
                        return CompletableFuture.failedFuture(new RuntimeException(message));
                    }

                    try {
                        return CompletableFuture.completedFuture(MAPPER.readTree(response.body()));
                    } catch (Exception e) {
                        return CompletableFuture.<JsonNode>failedFuture(e);
                    }
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<String> sendRequestWithRetryAsync(ObjectNode body) {
        return sendRequestWithRetryAsync(body, 0);
    }

    private CompletableFuture<String> sendRequestWithRetryAsync(ObjectNode body, int attempt) {
        try {
            return sendRawRequestAsync(body).thenCompose(root -> {
                String content = extractTextContent(root);
                if (content != null && !content.isBlank()) {
                    return CompletableFuture.completedFuture(content);
                }

                if (modelConfig.isReasoningContentFallback()) {
                    String reasoningContent = extractReasoningContent(root);
                    if (reasoningContent != null && !reasoningContent.isBlank()) {
                        log.info("Using reasoning_content as fallback for {}", clientName);
                        return CompletableFuture.completedFuture(reasoningContent);
                    }
                }

                if (attempt < EMPTY_RESPONSE_RETRIES) {
                    log.warn("Empty response from {} (attempt {}/{}), retrying...",
                            clientName, attempt + 1, EMPTY_RESPONSE_RETRIES + 1);
                    return sendRequestWithRetryAsync(body, attempt + 1);
                }

                return CompletableFuture.failedFuture(new RuntimeException(
                        clientName + " API returned empty content after "
                                + (EMPTY_RESPONSE_RETRIES + 1) + " attempts"
                ));
            });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    protected static String extractTextContent(JsonNode root) {
        String content = JsonUtils.extractTextFromResponse(root);
        AiTraceLogger.logTextSample("ai-response", "content_text", content);
        return content == null || content.isBlank() ? null : content;
    }

    protected static String extractReasoningContent(JsonNode root) {
        String reasoning = JsonUtils.extractReasoningTextFromResponse(root);
        AiTraceLogger.logTextSample("ai-response", "reasoning_text", reasoning);
        return reasoning == null || reasoning.isBlank() ? null : reasoning;
    }

    protected static String requireEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return val;
    }

    static boolean isRetryableFailure(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof IOException) {
            return true;
        }

        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalized = message.toLowerCase();
        return normalized.contains("rst_stream")
                || normalized.contains("goaway")
                || normalized.contains("connection reset")
                || normalized.contains("stream was reset")
                || normalized.contains("timed out")
                || normalized.contains("temporarily unavailable");
    }

    static boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }

    private CompletableFuture<JsonNode> scheduleRetry(
            ObjectNode body,
            String url,
            String jsonBody,
            int attempt,
            String reason
    ) {
        long delayMillis = retryDelayMillis(attempt);
        log.warn("Transient failure from {} (attempt {}/{}), retrying in {} ms: {}",
                clientName,
                attempt + 1,
                TRANSIENT_FAILURE_RETRIES + 1,
                delayMillis,
                reason);
        return CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> sendRawRequestAsync(body, url, jsonBody, attempt + 1));
    }

    private long retryDelayMillis(int attempt) {
        long delay = RETRY_BASE_DELAY_MILLIS * (1L << attempt);
        return Math.min(delay, RETRY_MAX_DELAY_MILLIS);
    }

    private void logRequest(ObjectNode body, String url, int attempt) {
        JsonNode messages = body.get("messages");
        JsonNode tools = body.get("tools");
        int messageCount = AiTraceLogger.arraySize(messages);
        int toolCount = AiTraceLogger.arraySize(tools);
        if (attempt == 0) {
            AiTraceLogger.logRequestSummary(
                    clientName, modelConfig.getModel(), messageCount, toolCount, url, log);
            AiTraceLogger.logRequestBody(clientName, body.toPrettyString());
            return;
        }

        AiTraceLogger.logRetryRequest(
                clientName,
                attempt + 1,
                modelConfig.getModel(),
                messageCount,
                toolCount,
                url,
                body.toPrettyString());
    }

    private void logResponse(HttpResponse<String> response) {
        AiTraceLogger.logResponse(clientName, response);
    }

}
