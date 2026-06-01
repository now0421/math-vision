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
import com.mathvision.util.ConcurrencyUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
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
import java.util.concurrent.CompletionException;

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
        this.clientName = modelConfig.resolveProvider() + ":" + modelConfig.getModel();
    }

    @Override
    public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
        try {
            MessageCreateParams params = buildMessageCreateParams(snapshot, null, null);
            return CompletableFuture.supplyAsync(() -> client.messages().create(params))
                    .thenApply(AnthropicAiClient::extractTextContent)
                    .handle((result, error) -> {
                        if (error == null) {
                            return result;
                        }
                        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                        log.error("{} chat failed: {}", clientName, describeError(cause), cause);
                        throw new CompletionException(new RuntimeException(
                                "AI chat failed: " + describeError(cause), cause));
                    });
        } catch (Exception e) {
            log.error("{} chat failed: {}", clientName, describeError(e), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat failed: " + describeError(e), e));
        }
    }

    @Override
    public CompletableFuture<JsonNode> chatWithToolsRawAsync(
            List<NodeConversationContext.Message> snapshot, String toolsJson) {
        try {
            List<Tool> tools = convertOpenAiFunctionTools(toolsJson);
            ToolChoiceTool toolChoice = tools.size() == 1
                    ? ToolChoiceTool.builder().name(tools.get(0).name()).build()
                    : null;
            MessageCreateParams params = buildMessageCreateParams(snapshot, tools, toolChoice);
            return CompletableFuture.supplyAsync(() -> client.messages().create(params))
                    .thenApply(AnthropicAiClient::wrapResponseAsOpenAiShape)
                    .handle((result, error) -> {
                        if (error == null) {
                            return result;
                        }
                        Throwable cause = ConcurrencyUtils.unwrapCompletionException(error);
                        log.error("{} chat (with tools) failed: {}", clientName, describeError(cause), cause);
                        throw new CompletionException(new RuntimeException(
                                "AI chat with tools failed: " + describeError(cause), cause));
                    });
        } catch (Exception e) {
            log.error("{} chat (with tools) failed: {}", clientName, describeError(e), e);
            return CompletableFuture.failedFuture(new RuntimeException(
                    "AI chat with tools failed: " + describeError(e), e));
        }
    }

    @Override
    public String providerName() {
        return clientName;
    }

    MessageCreateParams buildMessageCreateParams(
            List<NodeConversationContext.Message> snapshot,
            List<Tool> tools,
            ToolChoiceTool toolChoice) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelConfig.getModel())
                .maxTokens(modelConfig.getMaxOutputTokens())
                .messages(toAnthropicMessages(snapshot));

        String system = collectSystemMessages(snapshot);
        if (system != null && !system.isBlank()) {
            builder.system(system);
        }
        if (modelConfig.isAdaptiveThinking()) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        }
        OutputConfig.Effort effort = parseEffort(modelConfig.getEffort());
        if (effort != null) {
            builder.outputConfig(OutputConfig.builder().effort(effort).build());
        }
        if (tools != null && !tools.isEmpty()) {
            List<ToolUnion> toolUnions = new ArrayList<>(tools.size());
            for (Tool tool : tools) {
                toolUnions.add(ToolUnion.ofTool(tool));
            }
            builder.tools(toolUnions);
            if (toolChoice != null) {
                builder.toolChoice(toolChoice);
            }
        }
        return builder.build();
    }

    static List<MessageParam> toAnthropicMessages(List<NodeConversationContext.Message> snapshot) {
        List<MessageParam> messages = new ArrayList<>();
        if (snapshot == null) {
            return messages;
        }
        for (NodeConversationContext.Message source : snapshot) {
            String role = source.getRole();
            if ("system".equals(role)) {
                continue;
            }
            MessageParam.Role anthropicRole = "assistant".equals(role)
                    ? MessageParam.Role.ASSISTANT
                    : MessageParam.Role.USER;
            messages.add(MessageParam.builder()
                    .role(anthropicRole)
                    .content(source.getContent() == null ? "" : source.getContent())
                    .build());
        }
        return messages;
    }

    static String collectSystemMessages(List<NodeConversationContext.Message> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        StringBuilder system = new StringBuilder();
        for (NodeConversationContext.Message message : snapshot) {
            if (!"system".equals(message.getRole())) {
                continue;
            }
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            if (system.length() > 0) {
                system.append("\n\n");
            }
            system.append(message.getContent());
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
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(requireEnv(modelConfig.getApiKeyEnv()))
                .timeout(Duration.ofMinutes(10));
        String baseUrl = modelConfig.resolveBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank() && !DEFAULT_BASE_URL.equals(baseUrl.trim())) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + key + " is required");
        }
        return value;
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
