package com.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mathvision.config.ModelConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiError;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.AiToolCall;
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Shared base for providers exposing an OpenAI-compatible chat completions API.
 */
public abstract class AbstractOpenAiCompatibleAiClient implements AiClient {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EMPTY_RESPONSE_RETRIES = 2;

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
        this.clientName = AiClientSupport.clientName(modelConfig);
        this.apiKey = AiClientSupport.requireEnv(modelConfig.getApiKeyEnv());
        this.baseUrl = modelConfig.resolveBaseUrl();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
        try {
            ObjectNode body = buildRequestBody(request);
            boolean allowToolOnlyResponse = AiClientSupport.hasToolSchema(request);
            return sendAiResponseWithRetryAsync(body, allowToolOnlyResponse)
                    .handle(this::handleChatCompletion);
        } catch (Exception e) {
            log.error("{} chat failed: {}", clientName, e.getMessage(), e);
            return CompletableFuture.completedFuture(AiResponse.failure(buildError(e)));
        }
    }

    private AiResponse handleChatCompletion(AiResponse result, Throwable error) {
        if (error == null) {
            return result;
        }
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        log.error("{} chat failed: {}", clientName, cause.getMessage(), cause);
        return AiResponse.failure(buildError(cause));
    }

    private ObjectNode buildRequestBody(AiRequest request) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelConfig.getModel());
        body.put("temperature", modelConfig.getTemperature());
        body.put("max_tokens", modelConfig.getMaxOutputTokens());
        addThinking(body);
        body.set("messages", buildMessages(request != null ? request.getMessages() : List.of()));
        addTools(body, parseTools(request != null ? request.getToolsJson() : null));
        return body;
    }

    private static ArrayNode buildMessages(List<AiMessage> messages) {
        ArrayNode messagesArray = MAPPER.createArrayNode();
        if (messages == null) {
            return messagesArray;
        }
        for (AiMessage msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.getRole());
            List<AiContentPart> parts = msg.getParts() != null ? msg.getParts() : List.of();
            if (AiClientSupport.isTextOnly(parts)) {
                msgNode.put("content", AiClientSupport.textContent(parts));
                continue;
            }
            msgNode.set("content", buildContentParts(parts));
        }
        return messagesArray;
    }

    private static ArrayNode buildContentParts(List<AiContentPart> parts) {
        ArrayNode content = MAPPER.createArrayNode();
        for (AiContentPart part : parts) {
            if (part == null) {
                continue;
            }
            if ("text".equals(part.getType())) {
                content.addObject().put("type", "text").put("text", part.getText());
            } else if ("image".equals(part.getType())) {
                ObjectNode imgPart = content.addObject();
                imgPart.put("type", "image_url");
                imgPart.putObject("image_url")
                        .put("url", "data:" + part.getMimeType()
                                + ";base64," + part.getDataBase64());
            }
        }
        return content;
    }

    private ArrayNode parseTools(String toolsJson) throws IOException {
        if (toolsJson == null || toolsJson.isBlank()) {
            return null;
        }
        return (ArrayNode) MAPPER.readTree(toolsJson);
    }

    private CompletableFuture<JsonNode> sendJsonWithRetryAsync(ObjectNode body) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String jsonBody = MAPPER.writeValueAsString(body);
        return sendJsonWithRetryAsync(body, url, jsonBody, 0, 0, 0,
                AiRetryPolicy.initialTimeoutSeconds(modelConfig));
    }

    private CompletableFuture<JsonNode> sendJsonWithRetryAsync(
            ObjectNode body,
            String url,
            String jsonBody,
            int transientAttempt,
            int rateLimitAttempt,
            int timeoutAttempt,
            int timeoutSeconds
    ) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        logRequest(body, url, transientAttempt + rateLimitAttempt + timeoutAttempt, timeoutSeconds);
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<JsonNode>>handle((response, error) -> {
                    if (error != null) {
                        return handleTransportFailure(body, url, jsonBody, transientAttempt,
                                rateLimitAttempt, timeoutAttempt, timeoutSeconds, error);
                    }
                    return handleHttpResponse(body, url, jsonBody, transientAttempt,
                            rateLimitAttempt, timeoutAttempt, timeoutSeconds, response);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<JsonNode> handleTransportFailure(
            ObjectNode body,
            String url,
            String jsonBody,
            int transientAttempt,
            int rateLimitAttempt,
            int timeoutAttempt,
            int timeoutSeconds,
            Throwable error) {
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        if (AiRetryPolicy.isTimeoutFailure(cause)) {
            if (timeoutAttempt < AiRetryPolicy.timeoutRetryAttempts(modelConfig)) {
                int nextTimeoutSeconds = AiRetryPolicy.nextTimeoutSeconds(modelConfig, timeoutSeconds);
                AiRetryPolicy.logTimeoutRetry(log, clientName, timeoutSeconds,
                        timeoutAttempt, AiRetryPolicy.timeoutRetryAttempts(modelConfig), nextTimeoutSeconds);
                return sendJsonWithRetryAsync(body, url, jsonBody,
                        transientAttempt, rateLimitAttempt,
                        timeoutAttempt + 1, nextTimeoutSeconds);
            }
            AiRetryPolicy.logTimeoutExhausted(log, clientName, timeoutSeconds);
            return CompletableFuture.failedFuture(cause);
        }
        if (AiRetryPolicy.isRateLimitFailure(cause)
                && rateLimitAttempt < AiRetryPolicy.rateLimitRetries(modelConfig)) {
            return scheduleRateLimitRetry(body, url, jsonBody, transientAttempt,
                    rateLimitAttempt, timeoutAttempt, timeoutSeconds, cause.getMessage(),
                    java.util.Optional.empty());
        }
        if (transientAttempt < AiRetryPolicy.transientFailureRetries(modelConfig)
                && isRetryableFailure(cause)) {
            return scheduleTransientRetry(body, url, jsonBody, transientAttempt,
                    rateLimitAttempt, timeoutAttempt, timeoutSeconds, cause.getMessage());
        }
        return CompletableFuture.failedFuture(cause);
    }

    private CompletableFuture<JsonNode> handleHttpResponse(
            ObjectNode body,
            String url,
            String jsonBody,
            int transientAttempt,
            int rateLimitAttempt,
            int timeoutAttempt,
            int timeoutSeconds,
            HttpResponse<String> response) {
        logResponse(response);
        if (response.statusCode() == 200) {
            return parseResponseBody(response.body());
        }

        String message = clientName + " API returned HTTP " + response.statusCode()
                + ": " + response.body();
        if (AiRetryPolicy.isRateLimitStatusCode(response.statusCode())) {
            if (rateLimitAttempt < AiRetryPolicy.rateLimitRetries(modelConfig)) {
                return scheduleRateLimitRetry(body, url, jsonBody, transientAttempt,
                        rateLimitAttempt, timeoutAttempt, timeoutSeconds, message,
                        AiRetryPolicy.retryAfterHeader(response.headers()));
            }
            return CompletableFuture.failedFuture(new RuntimeException(message));
        }
        if (transientAttempt < AiRetryPolicy.transientFailureRetries(modelConfig)
                && isRetryableStatusCode(response.statusCode())) {
            return scheduleTransientRetry(body, url, jsonBody, transientAttempt,
                    rateLimitAttempt, timeoutAttempt, timeoutSeconds, message);
        }
        return CompletableFuture.failedFuture(new RuntimeException(message));
    }

    private CompletableFuture<JsonNode> parseResponseBody(String body) {
        try {
            return CompletableFuture.completedFuture(MAPPER.readTree(body));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<AiResponse> sendAiResponseWithRetryAsync(
            ObjectNode body,
            boolean allowToolOnlyResponse) {
        return sendAiResponseWithRetryAsync(body, allowToolOnlyResponse, 0);
    }

    private CompletableFuture<AiResponse> sendAiResponseWithRetryAsync(
            ObjectNode body,
            boolean allowToolOnlyResponse,
            int attempt) {
        try {
            return sendJsonWithRetryAsync(body).thenCompose(root -> {
                AiResponse response = parseResponse(root);
                if (response.getContent() != null && !response.getContent().isBlank()) {
                    return CompletableFuture.completedFuture(response);
                }
                if (!response.getToolCalls().isEmpty() || allowToolOnlyResponse) {
                    return CompletableFuture.completedFuture(response);
                }

                if (modelConfig.isReasoningContentFallback()) {
                    String reasoningContent = extractReasoningContent(root);
                    if (reasoningContent != null && !reasoningContent.isBlank()) {
                        log.info("Using reasoning_content as fallback for {}", clientName);
                        response.setContent(reasoningContent);
                        return CompletableFuture.completedFuture(response);
                    }
                }

                if (attempt < EMPTY_RESPONSE_RETRIES) {
                    log.warn("Empty response from {} (attempt {}/{}), retrying...",
                            clientName, attempt + 1, EMPTY_RESPONSE_RETRIES + 1);
                    return sendAiResponseWithRetryAsync(body, false, attempt + 1);
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
                || normalized.contains("temporarily unavailable");
    }

    static boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500;
    }

    private CompletableFuture<JsonNode> scheduleTransientRetry(
            ObjectNode body,
            String url,
            String jsonBody,
            int transientAttempt,
            int rateLimitAttempt,
            int timeoutAttempt,
            int timeoutSeconds,
            String reason
    ) {
        long delayMillis = AiRetryPolicy.retryDelayMillis(transientAttempt);
        log.warn("Transient failure from {} (attempt {}/{}), retrying in {} ms: {}",
                clientName,
                transientAttempt + 1,
                AiRetryPolicy.transientFailureRetries(modelConfig) + 1,
                delayMillis,
                reason);
        return CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> sendJsonWithRetryAsync(body, url, jsonBody,
                        transientAttempt + 1, rateLimitAttempt, timeoutAttempt, timeoutSeconds));
    }

    private CompletableFuture<JsonNode> scheduleRateLimitRetry(
            ObjectNode body,
            String url,
            String jsonBody,
            int transientAttempt,
            int rateLimitAttempt,
            int timeoutAttempt,
            int timeoutSeconds,
            String reason,
            java.util.Optional<String> retryAfterHeader
    ) {
        long delayMillis = AiRetryPolicy.rateLimitDelayMillis(modelConfig, rateLimitAttempt, retryAfterHeader);
        log.warn("Rate limit from {} (attempt {}/{}), retrying in {} ms: {}",
                clientName,
                rateLimitAttempt + 1,
                AiRetryPolicy.rateLimitRetries(modelConfig) + 1,
                delayMillis,
                reason);
        return CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> sendJsonWithRetryAsync(body, url, jsonBody,
                        transientAttempt, rateLimitAttempt + 1, timeoutAttempt, timeoutSeconds));
    }

    private void logRequest(ObjectNode body, String url, int attempt, int timeoutSeconds) {
        JsonNode messages = body.get("messages");
        JsonNode tools = body.get("tools");
        int messageCount = AiTraceLogger.arraySize(messages);
        int toolCount = AiTraceLogger.arraySize(tools);
        if (attempt == 0) {
            AiTraceLogger.logRequestSummary(
                    clientName, modelConfig.getModel(), messageCount, toolCount, url, log);
            log.debug("{} request timeout: {}s", clientName, timeoutSeconds);
            AiTraceLogger.logRequestBody(clientName, body.toPrettyString());
            return;
        }

        log.debug("{} retry request timeout: {}s", clientName, timeoutSeconds);
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

    private void addThinking(ObjectNode body) {
        String thinking = modelConfig.getThinking();
        if (thinking == null || thinking.isBlank()) {
            return;
        }
        body.putObject("thinking").put("type", thinking.trim().toLowerCase());
    }

    private void addTools(ObjectNode body, ArrayNode tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        body.set("tools", tools);
        addSingleToolChoice(body, tools);
    }

    private void addSingleToolChoice(ObjectNode body, ArrayNode tools) {
        if (tools.size() != 1) {
            return;
        }
        if (!canForceSingleToolChoice()) {
            return;
        }
        JsonNode function = tools.get(0).path("function");
        String name = function.path("name").asText("");
        if (name.isBlank()) {
            return;
        }
        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "function");
        toolChoice.putObject("function").put("name", name);
    }

    private boolean canForceSingleToolChoice() {
        String thinking = modelConfig.getThinking();
        String model = modelConfig.getModel() != null
                ? modelConfig.getModel().trim().toLowerCase()
                : "";
        if (model.startsWith("kimi-k2.") && !"disabled".equalsIgnoreCase(thinking)) {
            return false;
        }
        return true;
    }

    private AiResponse parseResponse(JsonNode root) {
        return AiResponse.success(extractTextContent(root), parseToolCalls(root), root);
    }

    private static List<AiToolCall> parseToolCalls(JsonNode root) {
        List<AiToolCall> calls = new ArrayList<>();
        JsonNode choices = root != null ? root.get("choices") : null;
        if (choices == null || choices.isEmpty()) {
            return calls;
        }
        JsonNode message = choices.get(0).get("message");
        JsonNode toolCalls = message != null ? message.get("tool_calls") : null;
        if (toolCalls == null || !toolCalls.isArray()) {
            return calls;
        }
        for (JsonNode rawCall : toolCalls) {
            JsonNode function = rawCall.path("function");
            AiToolCall call = new AiToolCall();
            call.setId(rawCall.path("id").asText(null));
            call.setName(function.path("name").asText(""));
            call.setRaw(rawCall);

            applyToolArguments(call, function.get("arguments"));
            calls.add(call);
        }
        return calls;
    }

    private static void applyToolArguments(AiToolCall call, JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return;
        }
        if (arguments.isObject() || arguments.isArray()) {
            call.setArguments(arguments);
            call.setArgumentsText(arguments.toString());
            return;
        }
        String argumentsText = arguments.asText("");
        call.setArgumentsText(argumentsText);
        call.setArguments(JsonUtils.parseTreeBestEffort(argumentsText));
    }

    private AiError buildError(Throwable cause) {
        AiError error = AiClientSupport.buildBaseError(cause, modelConfig);
        error.setTransientFailure(error.isTransientFailure() || isRetryableFailure(cause));
        return error;
    }
}
