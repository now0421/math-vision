package com.mathvision.util;

import com.mathvision.service.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRequestUtilsTest {

    @Test
    void usesCustomPlainTextParserForToolTextResponses() {
        FakeAiClient aiClient = new FakeAiClient(wrapTextResponse("problem"), "{\"ignored\":true}");

        JsonNode result = AiRequestUtils.requestJsonObjectAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "input mode",
                "user",
                "system",
                "[]",
                () -> { },
                text -> {
                    ObjectNode payload = JsonUtils.mapper().createObjectNode();
                    payload.put("input_mode", text.trim());
                    return payload;
                }
        ).join();

        assertEquals("problem", result.get("input_mode").asText());
        assertEquals(0, aiClient.chatCalls.get());
    }

    @Test
    void fallsBackToPlainChatWhenToolTextResponseHasNoJson() {
        FakeAiClient aiClient = new FakeAiClient(
                wrapTextResponse("not json at all"),
                "{\"ok\":true}"
        );

        JsonNode result = AiRequestUtils.requestJsonObjectAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "visual design",
                "user",
                "system",
                "[]",
                () -> { }
        ).join();

        assertTrue(result.get("ok").asBoolean());
        assertEquals(1, aiClient.chatCalls.get());
    }

    @Test
    void fallsBackWhenCustomValidatorRejectsToolPayload() {
        ObjectNode toolArguments = JsonUtils.mapper().createObjectNode();
        toolArguments.put("scene_name", "DemoScene");

        FakeAiClient aiClient = new FakeAiClient(
                wrapToolResponse(toolArguments),
                "{\"manimCode\":\"print('ok')\"}"
        );

        JsonNode result = AiRequestUtils.requestJsonObjectAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "code generation",
                "user",
                "system",
                "[]",
                () -> { },
                JsonUtils::parseTree,
                payload -> payload != null
                        && payload.has("manimCode")
                        && !payload.get("manimCode").asText("").isBlank()
        ).join();

        assertEquals("print('ok')", result.get("manimCode").asText());
        assertEquals(1, aiClient.chatCalls.get());
    }

    private static JsonNode wrapTextResponse(String text) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("content", text);
        return response;
    }

    private static JsonNode wrapToolResponse(JsonNode arguments) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "tool");
        function.set("arguments", arguments);
        return response;
    }

    private static final class FakeAiClient implements AiClient {
        private final JsonNode rawResponse;
        private final String plainChatResponse;
        private final AtomicInteger chatCalls = new AtomicInteger(0);

        private FakeAiClient(JsonNode rawResponse, String plainChatResponse) {
            this.rawResponse = rawResponse;
            this.plainChatResponse = plainChatResponse;
        }

        @Override
        public String chat(String userMessage, String systemPrompt) {
            chatCalls.incrementAndGet();
            return plainChatResponse;
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            return CompletableFuture.completedFuture(rawResponse);
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
