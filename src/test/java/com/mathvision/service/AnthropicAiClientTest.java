package com.mathvision.service;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mathvision.config.ModelConfig;
import com.mathvision.util.NodeConversationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicAiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void convertsSnapshotToAnthropicMessagesWithoutSystemMessages() {
        List<MessageParam> messages = AnthropicAiClient.toAnthropicMessages(List.of(
                new NodeConversationContext.Message("system", "system one"),
                new NodeConversationContext.Message("user", "hello"),
                new NodeConversationContext.Message("assistant", "hi"),
                new NodeConversationContext.Message("system", "system two")
        ));

        assertEquals(2, messages.size());
        assertEquals(MessageParam.Role.USER, messages.get(0).role());
        assertEquals("hello", messages.get(0).content().asString());
        assertEquals(MessageParam.Role.ASSISTANT, messages.get(1).role());
        assertEquals("hi", messages.get(1).content().asString());
    }

    @Test
    void combinesSystemMessagesIntoTopLevelSystem() {
        String system = AnthropicAiClient.collectSystemMessages(List.of(
                new NodeConversationContext.Message("system", "first"),
                new NodeConversationContext.Message("user", "hello"),
                new NodeConversationContext.Message("system", "second")
        ));

        assertEquals("first\n\nsecond", system);
    }

    @Test
    void buildsMessageParamsWithAdaptiveThinkingEffortAndNoSamplingParams() {
        AnthropicAiClient client = new AnthropicAiClient(modelConfig(), null);

        MessageCreateParams params = client.buildMessageCreateParams(List.of(
                new NodeConversationContext.Message("system", "system"),
                new NodeConversationContext.Message("user", "hello")
        ), List.of(), null);

        assertEquals("claude-opus-4-8", params.model().asString());
        assertEquals(64000, params.maxTokens());
        assertTrue(params.system().isPresent());
        assertEquals("system", params.system().get().asString());
        assertTrue(params.thinking().isPresent());
        assertTrue(params.outputConfig().isPresent());
        assertEquals(OutputConfig.Effort.HIGH, params.outputConfig().get().effort().orElseThrow());
        assertTrue(params.temperature().isEmpty());
        assertTrue(params.topP().isEmpty());
        assertTrue(params.topK().isEmpty());
    }

    @Test
    void convertsOpenAiFunctionToolsToAnthropicTools() {
        List<Tool> tools = AnthropicAiClient.convertOpenAiFunctionTools("["
                + "{\"type\":\"function\",\"function\":{"
                + "\"name\":\"write_payload\","
                + "\"description\":\"Write a payload\","
                + "\"parameters\":{"
                + "\"type\":\"object\","
                + "\"properties\":{\"value\":{\"type\":\"string\"}},"
                + "\"required\":[\"value\"],"
                + "\"additionalProperties\":false"
                + "}}}"
                + "]");

        assertEquals(1, tools.size());
        Tool tool = tools.get(0);
        assertEquals("write_payload", tool.name());
        assertEquals("Write a payload", tool.description().orElseThrow());
        assertTrue(tool.inputSchema().properties().orElseThrow()._additionalProperties().containsKey("value"));
        assertEquals(List.of("value"), tool.inputSchema().required().orElseThrow());
        assertEquals(false, tool.inputSchema()._additionalProperties().get("additionalProperties").asBoolean().orElse(true));
    }

    @Test
    void wrapsAnthropicToolUseResponseAsOpenAiCompatibleShape() throws Exception {
        Message response = MAPPER.readValue(TOOL_USE_RESPONSE_JSON, Message.class);

        JsonNode wrapped = AnthropicAiClient.wrapResponseAsOpenAiShape(response);

        assertEquals("Using a tool", wrapped.at("/choices/0/message/content").asText());
        assertEquals("write_payload", wrapped.at("/choices/0/message/tool_calls/0/function/name").asText());
        assertEquals("ok", wrapped.at("/choices/0/message/tool_calls/0/function/arguments/value").asText());
    }

    @Test
    void extractsPlainTextResponse() throws Exception {
        Message response = MAPPER.readValue(TEXT_ONLY_RESPONSE_JSON, Message.class);

        assertEquals("line 1\nline 2", AnthropicAiClient.extractTextContent(response));
    }

    @Test
    void buildsForcedToolChoiceWhenSingleToolIsPresent() {
        List<Tool> tools = AnthropicAiClient.convertOpenAiFunctionTools("["
                + "{\"type\":\"function\",\"function\":{\"name\":\"write_payload\",\"parameters\":{\"type\":\"object\"}}}"
                + "]");
        ToolChoiceTool toolChoice = ToolChoiceTool.builder().name(tools.get(0).name()).build();
        AnthropicAiClient client = new AnthropicAiClient(modelConfig(), null);

        MessageCreateParams params = client.buildMessageCreateParams(List.of(
                new NodeConversationContext.Message("user", "hello")
        ), tools, toolChoice);

        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
        assertNotNull(params.toolChoice().orElseThrow());
    }

    private static ModelConfig modelConfig() {
        ModelConfig config = new ModelConfig();
        config.setModel("claude-opus-4-8");
        config.setProvider("anthropic");
        config.setApiKeyEnv("ANTHROPIC_API_KEY");
        config.setBaseUrl("https://api.anthropic.com");
        config.setMaxOutputTokens(64000);
        config.setMaxInputTokens(1000000);
        config.setAdaptiveThinking(true);
        config.setEffort("high");
        config.setSupportsVision(true);
        return config;
    }

    // Realistic Anthropic API response JSON with tool_use
    private static final String TOOL_USE_RESPONSE_JSON = "{"
            + "\"id\":\"msg_test\","
            + "\"type\":\"message\","
            + "\"role\":\"assistant\","
            + "\"model\":\"claude-opus-4-8\","
            + "\"stop_reason\":\"tool_use\","
            + "\"content\":["
            + "  {\"type\":\"text\",\"text\":\"Using a tool\",\"citations\":[]},"
            + "  {\"type\":\"tool_use\",\"id\":\"toolu_test\",\"name\":\"write_payload\","
            + "   \"input\":{\"value\":\"ok\"}}"
            + "],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
            + "  \"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}"
            + "}";

    // Realistic Anthropic API response JSON with text only
    private static final String TEXT_ONLY_RESPONSE_JSON = "{"
            + "\"id\":\"msg_test\","
            + "\"type\":\"message\","
            + "\"role\":\"assistant\","
            + "\"model\":\"claude-opus-4-8\","
            + "\"stop_reason\":\"end_turn\","
            + "\"content\":["
            + "  {\"type\":\"text\",\"text\":\"line 1\",\"citations\":[]},"
            + "  {\"type\":\"text\",\"text\":\"line 2\",\"citations\":[]}"
            + "],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,"
            + "  \"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}"
            + "}";
}
