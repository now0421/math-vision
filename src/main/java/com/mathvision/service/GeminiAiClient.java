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
import org.slf4j.LoggerFactory;

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
    private final String clientName;

    public GeminiAiClient(ModelConfig modelConfig) {
        this(modelConfig, AiClientSupport.requireEnv(modelConfig.getApiKeyEnv()));
    }

    GeminiAiClient(ModelConfig modelConfig, String apiKey) {
        this.apiKey = apiKey;
        this.modelConfig = modelConfig;
        this.clientName = AiClientSupport.clientName(modelConfig);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
        try {
            ObjectNode body = buildGenerateContentBody(request);
            return sendGenerateContentWithRetryAsync(body)
                    .handle(this::handleChatCompletion);
        } catch (Exception e) {
            log.error("Gemini chat failed: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(AiResponse.failure(buildError(e)));
        }
    }

    private AiResponse handleChatCompletion(AiResponse result, Throwable error) {
        if (error == null) {
            return result;
        }
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        log.error("Gemini chat failed: {}", cause.getMessage(), cause);
        return AiResponse.failure(buildError(cause));
    }

    private CompletableFuture<AiResponse> sendGenerateContentWithRetryAsync(ObjectNode body) throws Exception {
        String url = modelConfig.resolveBaseUrl().replaceAll("/+$", "")
                + "/" + modelConfig.getModel() + ":generateContent?key=" + apiKey;

        String jsonBody = mapper.writeValueAsString(body);
        return sendGenerateContentWithRetryAsync(body, url, jsonBody, 0, 0, 0,
                AiRetryPolicy.initialTimeoutSeconds(modelConfig));
    }

    private CompletableFuture<AiResponse> sendGenerateContentWithRetryAsync(ObjectNode body,
                                                                            String url,
                                                                            String jsonBody,
                                                                            int transientAttempt,
                                                                            int rateLimitAttempt,
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
                .<CompletableFuture<AiResponse>>handle((response, error) -> {
                    if (error != null) {
                        return handleTransportFailure(body, url, jsonBody, transientAttempt,
                                rateLimitAttempt, timeoutAttempt, timeoutSeconds, error);
                    }
                    return handleHttpResponse(body, url, jsonBody, transientAttempt,
                            rateLimitAttempt, timeoutAttempt, timeoutSeconds, response);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<AiResponse> handleTransportFailure(ObjectNode body,
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
                return sendGenerateContentWithRetryAsync(body, url, jsonBody,
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
                && AiRetryPolicy.isRetryableTransportFailure(cause)) {
            return scheduleTransientRetry(body, url, jsonBody, transientAttempt,
                    rateLimitAttempt, timeoutAttempt, timeoutSeconds, cause.getMessage());
        }
        return CompletableFuture.failedFuture(cause);
    }

    private CompletableFuture<AiResponse> handleHttpResponse(ObjectNode body,
                                                             String url,
                                                             String jsonBody,
                                                             int transientAttempt,
                                                             int rateLimitAttempt,
                                                             int timeoutAttempt,
                                                             int timeoutSeconds,
                                                             HttpResponse<String> response) {
        AiTraceLogger.logResponse("Gemini", response);
        if (response.statusCode() == 200) {
            return parseGenerateContentResponse(response.body());
        }

        String message = "Gemini API returned HTTP " + response.statusCode()
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
                && AiRetryPolicy.isRetryableStatusCode(response.statusCode())) {
            return scheduleTransientRetry(body, url, jsonBody, transientAttempt,
                    rateLimitAttempt, timeoutAttempt, timeoutSeconds, message);
        }
        return CompletableFuture.failedFuture(new RuntimeException(message));
    }

    private CompletableFuture<AiResponse> parseGenerateContentResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            return CompletableFuture.completedFuture(parseGenerateContentResponse(root));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static AiResponse parseGenerateContentResponse(JsonNode root) {
        JsonNode candidates = root.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini API returned no candidates");
        }
        String text = extractCandidateText(candidates.get(0));
        AiTraceLogger.logTextSample("Gemini", "content_text", text);
        return AiResponse.success(text, extractToolCalls(candidates.get(0)), root);
    }

    private static String extractCandidateText(JsonNode candidate) {
        JsonNode parts = candidate.path("content").path("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String value = part.path("text").asText("");
            if (value.isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(value);
        }
        return text.toString();
    }

    static List<AiToolCall> extractToolCalls(JsonNode candidate) {
        List<AiToolCall> calls = new ArrayList<>();
        JsonNode parts = candidate.path("content").path("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            return calls;
        }

        for (JsonNode part : parts) {
            JsonNode functionCall = firstPresent(part, "functionCall", "function_call");
            if (functionCall == null || functionCall.isMissingNode() || functionCall.isNull()) {
                continue;
            }
            AiToolCall call = new AiToolCall();
            call.setName(functionCall.path("name").asText(""));
            call.setRaw(functionCall);
            applyToolArguments(call, functionCall.get("args"));
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

    private CompletableFuture<AiResponse> scheduleTransientRetry(ObjectNode body,
                                                                 String url,
                                                                 String jsonBody,
                                                                 int transientAttempt,
                                                                 int rateLimitAttempt,
                                                                 int timeoutAttempt,
                                                                 int timeoutSeconds,
                                                                 String reason) {
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
                .thenCompose(ignored -> sendGenerateContentWithRetryAsync(body, url, jsonBody,
                        transientAttempt + 1, rateLimitAttempt, timeoutAttempt, timeoutSeconds));
    }

    private CompletableFuture<AiResponse> scheduleRateLimitRetry(ObjectNode body,
                                                                 String url,
                                                                 String jsonBody,
                                                                 int transientAttempt,
                                                                 int rateLimitAttempt,
                                                                 int timeoutAttempt,
                                                                 int timeoutSeconds,
                                                                 String reason,
                                                                 java.util.Optional<String> retryAfterHeader) {
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
                .thenCompose(ignored -> sendGenerateContentWithRetryAsync(body, url, jsonBody,
                        transientAttempt, rateLimitAttempt + 1, timeoutAttempt, timeoutSeconds));
    }

    ObjectNode buildGenerateContentBody(AiRequest request) {
        ObjectNode body = mapper.createObjectNode();
        List<AiMessage> messages = request != null ? request.getMessages() : List.of();
        addSystemInstruction(body, messages);
        addContents(body, messages);
        addTools(body, request != null ? request.getToolsJson() : null);
        addGenerationConfig(body);
        return body;
    }

    private void addSystemInstruction(ObjectNode body, List<AiMessage> messages) {
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
    }

    private void addContents(ObjectNode body, List<AiMessage> messages) {
        ArrayNode contents = body.putArray("contents");
        for (AiMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                continue;
            }
            ObjectNode entry = contents.addObject();
            entry.put("role", "assistant".equals(msg.getRole()) ? "model" : msg.getRole());
            ArrayNode parts = entry.putArray("parts");
            for (AiContentPart part : msg.getParts()) {
                addGeminiPart(parts, part);
            }
        }
    }

    private void addGeminiPart(ArrayNode parts, AiContentPart part) {
        if ("text".equals(part.getType())) {
            parts.addObject().put("text", part.getText());
        } else if ("image".equals(part.getType())) {
            ObjectNode inlineData = parts.addObject().putObject("inline_data");
            inlineData.put("mime_type", part.getMimeType());
            inlineData.put("data", part.getDataBase64());
        }
    }

    private void addGenerationConfig(ObjectNode body) {
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", modelConfig.getTemperature());
        generationConfig.put("maxOutputTokens", modelConfig.getMaxOutputTokens());
    }

    private void addTools(ObjectNode body, String toolsJson) {
        ArrayNode tools = convertOpenAiFunctionTools(toolsJson);
        if (tools.isEmpty()) {
            return;
        }
        body.set("tools", tools);

        ObjectNode functionCallingConfig = body.putObject("toolConfig")
                .putObject("functionCallingConfig");
        functionCallingConfig.put("mode", functionDeclarationCount(tools) == 1 ? "ANY" : "AUTO");
        addAllowedFunctionNames(functionCallingConfig, tools);
    }

    static ArrayNode convertOpenAiFunctionTools(String toolsJson) {
        ArrayNode geminiTools = mapper.createArrayNode();
        if (toolsJson == null || toolsJson.isBlank()) {
            return geminiTools;
        }

        JsonNode root = JsonUtils.parseTree(toolsJson);
        if (!root.isArray()) {
            throw new IllegalArgumentException("Tool schema must be a JSON array");
        }

        ArrayNode declarations = mapper.createArrayNode();
        for (JsonNode item : root) {
            JsonNode function = item.path("function");
            if (!"function".equals(item.path("type").asText()) || function.isMissingNode()) {
                continue;
            }
            ObjectNode declaration = toGeminiFunctionDeclaration(function);
            if (declaration != null) {
                declarations.add(declaration);
            }
        }

        if (!declarations.isEmpty()) {
            geminiTools.addObject().set("functionDeclarations", declarations);
        }
        return geminiTools;
    }

    private static ObjectNode toGeminiFunctionDeclaration(JsonNode function) {
        String name = function.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }

        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", name);
        String description = function.path("description").asText("");
        declaration.put("description", description.isBlank()
                ? "Return structured output for " + name + "."
                : description);

        JsonNode parameters = function.get("parameters");
        if (parameters != null && !parameters.isNull() && !parameters.isMissingNode()) {
            declaration.set("parametersJsonSchema", parameters);
        }
        return declaration;
    }

    private static void addAllowedFunctionNames(ObjectNode functionCallingConfig, ArrayNode tools) {
        ArrayNode allowedNames = mapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode declarations = firstPresent(tool, "functionDeclarations", "function_declarations");
            if (declarations == null || !declarations.isArray()) {
                continue;
            }
            for (JsonNode declaration : declarations) {
                String name = declaration.path("name").asText("");
                if (!name.isBlank()) {
                    allowedNames.add(name);
                }
            }
        }
        if (allowedNames.size() == 1) {
            functionCallingConfig.set("allowedFunctionNames", allowedNames);
        }
    }

    private static int functionDeclarationCount(ArrayNode tools) {
        int count = 0;
        for (JsonNode tool : tools) {
            JsonNode declarations = firstPresent(tool, "functionDeclarations", "function_declarations");
            if (declarations != null && declarations.isArray()) {
                count += declarations.size();
            }
        }
        return count;
    }

    private static JsonNode firstPresent(JsonNode node, String firstField, String secondField) {
        if (node == null) {
            return null;
        }
        JsonNode first = node.get(firstField);
        if (first != null) {
            return first;
        }
        return node.get(secondField);
    }

    private AiError buildError(Throwable cause) {
        return AiClientSupport.buildBaseError(cause, modelConfig);
    }
}
