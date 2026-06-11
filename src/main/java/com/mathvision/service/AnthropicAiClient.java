package com.mathvision.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Official Anthropic Messages API client.
 */
public class AnthropicAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAiClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final ModelConfig modelConfig;
    private final String clientName;
    private final AnthropicClient client;

    public AnthropicAiClient(ModelConfig modelConfig) {
        this(modelConfig, buildClient(modelConfig));
    }

    AnthropicAiClient(ModelConfig modelConfig, AnthropicClient client) {
        this.modelConfig = modelConfig;
        this.client = client;
        this.clientName = AiClientSupport.clientName(modelConfig);
    }

    @Override
    public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
        try {
            PreparedMessageRequest preparedRequest = prepareMessageRequest(request);
            return createMessageWithRetry(preparedRequest.params, preparedRequest.withTools,
                            AiRetryPolicy.initialTimeoutSeconds(modelConfig), 0, 0, 0)
                    .thenApply(AnthropicAiClient::toAiResponse)
                    .handle(this::handleChatCompletion);
        } catch (Exception e) {
            log.error("{} chat failed: {}", clientName, describeError(e), e);
            return CompletableFuture.completedFuture(AiResponse.failure(buildError(e)));
        }
    }

    private PreparedMessageRequest prepareMessageRequest(AiRequest request) {
        ensureTextOnly(request);
        List<Tool> tools = convertOpenAiFunctionTools(request != null ? request.getToolsJson() : null);
        MessageCreateParams params = buildMessageCreateParams(
                request != null ? request.getMessages() : List.of(),
                tools,
                buildToolChoice(tools));
        return new PreparedMessageRequest(params, !tools.isEmpty());
    }

    private AiResponse handleChatCompletion(AiResponse result, Throwable error) {
        if (error == null) {
            return result;
        }
        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
        log.error("{} chat failed: {}", clientName, describeError(cause), cause);
        return AiResponse.failure(buildError(cause));
    }

    private CompletableFuture<Message> createMessageWithRetry(MessageCreateParams params,
                                                              boolean withTools,
                                                              int timeoutSeconds,
                                                              int transientAttempt,
                                                              int rateLimitAttempt,
                                                              int timeoutAttempt) {
        AnthropicClient requestClient = timeoutSeconds == AiRetryPolicy.initialTimeoutSeconds(modelConfig)
                ? client
                : buildClient(modelConfig, timeoutSeconds);
        return CompletableFuture.supplyAsync(() -> requestClient.messages().create(params))
                .handle((message, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(message);
                    }
                    Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                    if (AiRetryPolicy.isTimeoutFailure(cause)) {
                        if (timeoutAttempt < AiRetryPolicy.timeoutRetryAttempts(modelConfig)) {
                            int nextTimeoutSeconds = AiRetryPolicy.nextTimeoutSeconds(modelConfig, timeoutSeconds);
                            AiRetryPolicy.logTimeoutRetry(log, clientName, timeoutSeconds,
                                    timeoutAttempt, AiRetryPolicy.timeoutRetryAttempts(modelConfig), nextTimeoutSeconds);
                            return createMessageWithRetry(params, withTools, nextTimeoutSeconds,
                                    transientAttempt, rateLimitAttempt, timeoutAttempt + 1);
                        }
                        AiRetryPolicy.logTimeoutExhausted(log, clientName, timeoutSeconds);
                        return CompletableFuture.<Message>failedFuture(cause);
                    }
                    if (isRateLimitAnthropicFailure(cause)
                            && rateLimitAttempt < AiRetryPolicy.rateLimitRetries(modelConfig)) {
                        return scheduleRateLimitMessageRetry(params, withTools, timeoutSeconds,
                                transientAttempt, rateLimitAttempt, timeoutAttempt, cause);
                    }
                    if (transientAttempt < AiRetryPolicy.transientFailureRetries(modelConfig)
                            && isRetryableAnthropicFailure(cause)) {
                        return scheduleMessageRetry(params, withTools, timeoutSeconds,
                                transientAttempt, rateLimitAttempt, timeoutAttempt, cause);
                    }
                    return CompletableFuture.<Message>failedFuture(cause);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<Message> scheduleMessageRetry(MessageCreateParams params,
                                                            boolean withTools,
                                                            int timeoutSeconds,
                                                            int transientAttempt,
                                                            int rateLimitAttempt,
                                                            int timeoutAttempt,
                                                            Throwable cause) {
        long delayMillis = AiRetryPolicy.retryDelayMillis(transientAttempt);
        log.warn("Transient failure from {}{} (attempt {}/{}), retrying in {} ms: {}",
                clientName,
                withTools ? " with tools" : "",
                transientAttempt + 1,
                AiRetryPolicy.transientFailureRetries(modelConfig) + 1,
                delayMillis,
                describeError(cause));
        return CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> createMessageWithRetry(params, withTools, timeoutSeconds,
                        transientAttempt + 1, rateLimitAttempt, timeoutAttempt));
    }

    private CompletableFuture<Message> scheduleRateLimitMessageRetry(MessageCreateParams params,
                                                                     boolean withTools,
                                                                     int timeoutSeconds,
                                                                     int transientAttempt,
                                                                     int rateLimitAttempt,
                                                                     int timeoutAttempt,
                                                                     Throwable cause) {
        long delayMillis = AiRetryPolicy.rateLimitDelayMillis(modelConfig, rateLimitAttempt);
        log.warn("Rate limit from {}{} (attempt {}/{}), retrying in {} ms: {}",
                clientName,
                withTools ? " with tools" : "",
                rateLimitAttempt + 1,
                AiRetryPolicy.rateLimitRetries(modelConfig) + 1,
                delayMillis,
                describeError(cause));
        return CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> createMessageWithRetry(params, withTools, timeoutSeconds,
                        transientAttempt, rateLimitAttempt + 1, timeoutAttempt));
    }

    private static boolean isRetryableAnthropicFailure(Throwable error) {
        if (error instanceof AnthropicServiceException) {
            return AiRetryPolicy.isRetryableStatusCode(((AnthropicServiceException) error).statusCode());
        }
        return AiRetryPolicy.isRetryableTransportFailure(error);
    }

    private static boolean isRateLimitAnthropicFailure(Throwable error) {
        if (error instanceof AnthropicServiceException) {
            return AiRetryPolicy.isRateLimitStatusCode(((AnthropicServiceException) error).statusCode());
        }
        return AiRetryPolicy.isRateLimitFailure(error);
    }

    MessageCreateParams buildMessageCreateParams(
            List<AiMessage> messages,
            List<Tool> tools,
            ToolChoiceTool toolChoice) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelConfig.getModel())
                .maxTokens(modelConfig.getMaxOutputTokens())
                .messages(toAnthropicMessages(messages));

        applySystemPrompt(builder, messages);
        applyModelOptions(builder);
        applyTools(builder, tools, toolChoice);
        return builder.build();
    }

    private void applySystemPrompt(MessageCreateParams.Builder builder, List<AiMessage> messages) {
        String system = collectSystemMessages(messages);
        if (system != null && !system.isBlank()) {
            builder.system(system);
        }
    }

    private void applyModelOptions(MessageCreateParams.Builder builder) {
        if (modelConfig.isAdaptiveThinking()) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }
        OutputConfig.Effort effort = parseEffort(modelConfig.getEffort());
        if (effort != null) {
            builder.outputConfig(OutputConfig.builder().effort(effort).build());
        }
    }

    private void applyTools(MessageCreateParams.Builder builder,
                            List<Tool> tools,
                            ToolChoiceTool toolChoice) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        List<ToolUnion> toolUnions = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            toolUnions.add(ToolUnion.ofTool(tool));
        }
        builder.tools(toolUnions);
        if (toolChoice != null) {
            builder.toolChoice(toolChoice);
        }
    }

    private static ToolChoiceTool buildToolChoice(List<Tool> tools) {
        return tools != null && tools.size() == 1
                ? ToolChoiceTool.builder().name(tools.get(0).name()).build()
                : null;
    }

    static List<MessageParam> toAnthropicMessages(List<AiMessage> sourceMessages) {
        List<MessageParam> messages = new ArrayList<>();
        if (sourceMessages == null) {
            return messages;
        }
        for (AiMessage source : sourceMessages) {
            String role = source.getRole();
            if ("system".equals(role)) {
                continue;
            }
            MessageParam.Role anthropicRole = "assistant".equals(role)
                    ? MessageParam.Role.ASSISTANT
                    : MessageParam.Role.USER;
            messages.add(MessageParam.builder()
                    .role(anthropicRole)
                    .content(AiClientSupport.textContent(source))
                    .build());
        }
        return messages;
    }

    static String collectSystemMessages(List<AiMessage> sourceMessages) {
        if (sourceMessages == null || sourceMessages.isEmpty()) {
            return null;
        }
        StringBuilder system = new StringBuilder();
        for (AiMessage message : sourceMessages) {
            if (!"system".equals(message.getRole())) {
                continue;
            }
            String content = AiClientSupport.textContent(message);
            if (content.isBlank()) {
                continue;
            }
            if (system.length() > 0) {
                system.append("\n\n");
            }
            system.append(content);
        }
        return system.length() > 0 ? system.toString() : null;
    }

    static List<Tool> convertOpenAiFunctionTools(String toolsJson) {
        List<Tool> tools = new ArrayList<>();
        if (toolsJson == null || toolsJson.isBlank()) {
            return tools;
        }

        JsonNode root = JsonUtils.parseTree(toolsJson);
        if (!root.isArray()) {
            throw new IllegalArgumentException("Tool schema must be a JSON array");
        }

        for (JsonNode item : root) {
            JsonNode function = item.path("function");
            if (!"function".equals(item.path("type").asText()) || function.isMissingNode()) {
                continue;
            }
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            Tool.Builder toolBuilder = Tool.builder()
                    .name(name)
                    .inputSchema(toAnthropicInputSchema(function.path("parameters")));
            String description = function.path("description").asText("");
            if (!description.isBlank()) {
                toolBuilder.description(description);
            }
            tools.add(toolBuilder.build());
        }
        return tools;
    }

    static Tool.InputSchema toAnthropicInputSchema(JsonNode parameters) {
        JsonNode schema = parameters == null || parameters.isMissingNode() || parameters.isNull()
                ? JsonUtils.parseTree("{\"type\":\"object\",\"properties\":{}}")
                : parameters;

        Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
        JsonNode typeNode = schema.get("type");
        builder.type(typeNode == null || typeNode.isNull()
                ? JsonValue.from("object")
                : JsonValue.fromJsonNode(typeNode));

        JsonNode propertiesNode = schema.get("properties");
        if (propertiesNode != null && propertiesNode.isObject()) {
            Tool.InputSchema.Properties.Builder propertiesBuilder = Tool.InputSchema.Properties.builder();
            Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                propertiesBuilder.putAdditionalProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue()));
            }
            builder.properties(propertiesBuilder.build());
        }

        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            List<String> required = new ArrayList<>();
            for (JsonNode item : requiredNode) {
                if (item.isTextual()) {
                    required.add(item.asText());
                }
            }
            if (!required.isEmpty()) {
                builder.required(required);
            }
        }

        Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if ("type".equals(key) || "properties".equals(key) || "required".equals(key)) {
                continue;
            }
            builder.putAdditionalProperty(key, JsonValue.fromJsonNode(field.getValue()));
        }
        return builder.build();
    }

    static JsonNode wrapResponseAsOpenAiShape(Message message) {
        return toOpenAiCompatibleRaw(message);
    }

    static JsonNode toOpenAiCompatibleRaw(Message message) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        ObjectNode wrappedMessage = choice.putObject("message");
        wrappedMessage.put("content", extractTextContent(message));
        ArrayNode toolCalls = wrappedMessage.putArray("tool_calls");

        for (ContentBlock block : message.content()) {
            Optional<ToolUseBlock> toolUse = block.toolUse();
            if (toolUse.isEmpty()) {
                continue;
            }
            ToolUseBlock value = toolUse.get();
            ObjectNode toolCall = toolCalls.addObject();
            toolCall.put("id", value.id());
            toolCall.put("type", "function");
            ObjectNode function = toolCall.putObject("function");
            function.put("name", value.name());
            function.set("arguments", toJacksonNode(value._input()));
        }
        return root;
    }

    static String extractTextContent(Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            Optional<TextBlock> textBlock = block.text();
            if (textBlock.isEmpty()) {
                continue;
            }
            String value = textBlock.get().text();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(value.trim());
        }
        String result = text.toString();
        AiTraceLogger.logTextSample("anthropic-response", "content_text", result);
        return result;
    }

    private static JsonNode toJacksonNode(JsonValue value) {
        return JsonUtils.mapper().valueToTree(value.convert(Object.class));
    }

    private static AiResponse toAiResponse(Message message) {
        JsonNode raw = toOpenAiCompatibleRaw(message);
        return AiResponse.success(extractTextContent(message), extractToolCalls(message), raw);
    }

    private static List<AiToolCall> extractToolCalls(Message message) {
        List<AiToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            Optional<ToolUseBlock> toolUse = block.toolUse();
            if (toolUse.isEmpty()) {
                continue;
            }
            toolCalls.add(toAiToolCall(toolUse.get()));
        }
        return toolCalls;
    }

    private static AiToolCall toAiToolCall(ToolUseBlock toolUse) {
        JsonNode arguments = toJacksonNode(toolUse._input());
        AiToolCall call = new AiToolCall();
        call.setId(toolUse.id());
        call.setName(toolUse.name());
        call.setArguments(arguments);
        call.setArgumentsText(arguments != null ? arguments.toString() : "");
        call.setRaw(arguments);
        return call;
    }

    private static void ensureTextOnly(AiRequest request) {
        if (request == null || request.getMessages() == null) {
            return;
        }
        for (AiMessage message : request.getMessages()) {
            if (message.getParts() == null) {
                continue;
            }
            for (AiContentPart part : message.getParts()) {
                if ("image".equals(part.getType())) {
                    throw new UnsupportedOperationException("Anthropic image inputs are not supported by this client yet");
                }
            }
        }
    }

    private static OutputConfig.Effort parseEffort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("-", "");
        switch (normalized) {
            case "low":
                return OutputConfig.Effort.LOW;
            case "medium":
                return OutputConfig.Effort.MEDIUM;
            case "high":
                return OutputConfig.Effort.HIGH;
            case "xhigh":
                return OutputConfig.Effort.XHIGH;
            case "max":
                return OutputConfig.Effort.MAX;
            default:
                return OutputConfig.Effort.of(value.trim());
        }
    }

    private static AnthropicClient buildClient(ModelConfig modelConfig) {
        return buildClient(modelConfig, AiRetryPolicy.initialTimeoutSeconds(modelConfig));
    }

    private static AnthropicClient buildClient(ModelConfig modelConfig, int timeoutSeconds) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(AiClientSupport.requireEnv(modelConfig.getApiKeyEnv()))
                .timeout(Duration.ofSeconds(timeoutSeconds));
        String baseUrl = modelConfig.resolveBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !DEFAULT_BASE_URL.equals(baseUrl.trim())) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    private AiError buildError(Throwable cause) {
        AiError error = AiClientSupport.buildBaseError(cause, modelConfig);
        if (cause instanceof AnthropicServiceException) {
            error.setHttpStatus(((AnthropicServiceException) cause).statusCode());
        }
        error.setRateLimited(error.isRateLimited() || isRateLimitAnthropicFailure(cause));
        error.setTransientFailure(error.isTransientFailure() || isRetryableAnthropicFailure(cause));
        return error;
    }

    private static final class PreparedMessageRequest {
        private final MessageCreateParams params;
        private final boolean withTools;

        private PreparedMessageRequest(MessageCreateParams params, boolean withTools) {
            this.params = params;
            this.withTools = withTools;
        }
    }

    private static String describeError(Throwable error) {
        if (error instanceof AnthropicServiceException) {
            AnthropicServiceException serviceError = (AnthropicServiceException) error;
            String type = serviceError.errorType()
                    .map(errorType -> errorType.asString())
                    .orElse("unknown_error");
            return "HTTP " + serviceError.statusCode() + " " + type + ": " + serviceError.getMessage();
        }
        return error.getMessage();
    }
}
