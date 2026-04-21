package com.mathvision.util;

import com.mathvision.service.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void explicitSnapshotRequestsReuseFrozenContextAndReturnTranscript() {
        SnapshotAwareAiClient aiClient = new SnapshotAwareAiClient(wrapToolResponse(
                JsonUtils.parseTree("{\"equations\":[\"x=1\"],\"definitions\":{}}")
        ));

        AiRequestUtils.JsonObjectResult response = AiRequestUtils.requestJsonObjectResultAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "math enrichment",
                List.of(
                        new NodeConversationContext.Message("system", "system prompt"),
                        new NodeConversationContext.Message("user", "older user"),
                        new NodeConversationContext.Message("assistant", "older assistant")
                ),
                1000,
                "current user",
                "[]",
                () -> { }
        ).join();

        assertEquals("x=1", response.getPayload().get("equations").get(0).asText());
        assertTrue(response.getAssistantTranscript().contains("tool"));
        assertEquals(List.of("system", "user", "assistant", "user"), aiClient.lastSnapshotRoles);
        assertEquals("current user", aiClient.lastSnapshotUserContent);
    }

    @Test
    void extractedTextPrefersConfiguredPayloadField() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("manimCode", "from manim import *\nclass MainScene(Scene):\n    pass");

        AiRequestUtils.ExtractedTextResult result = AiRequestUtils.requestExtractedTextResultAsync(
                new FakeAiClient(wrapToolResponse(arguments), "ignored"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                "user",
                "system",
                "[]",
                () -> { },
                List.of("manimCode"),
                text -> text == null ? null : text.trim(),
                text -> text != null && !text.isBlank()
        ).join();

        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getExtractedText());
        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getAssistantTranscript());
        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getPayload().get("manimCode").asText());
    }

    @Test
    void extractedTextFallsBackToCodeBlockWhenPayloadFieldMissing() {
        String responseText = "```python\nfrom manim import *\nclass MainScene(Scene):\n    pass\n```";

        String extractedText = AiRequestUtils.requestExtractedTextAsync(
                new FakeAiClient(wrapTextResponse(responseText), "ignored"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                "user",
                "system",
                "[]",
                () -> { },
                List.of("manimCode"),
                text -> text == null ? null : text.trim(),
                text -> text != null && !text.isBlank()
        ).join();

        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", extractedText);
    }

    @Test
    void extractedTextFallsBackToWholeResponseWhenNoCodeBlockExists() {
        String extractedText = AiRequestUtils.requestExtractedTextAsync(
                new FakeAiClient(wrapTextResponse("problem"), "ignored"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "classification",
                "user",
                "system",
                "[]",
                () -> { },
                List.of("input_mode"),
                text -> text == null ? null : text.trim(),
                text -> text != null && !text.isBlank()
        ).join();

        assertEquals("problem", extractedText);
    }

    @Test
    void extractedTextReturnsNullWhenNothingUsableIsFound() {
        String extractedText = AiRequestUtils.requestExtractedTextAsync(
                new FakeAiClient(wrapTextResponse("   "), "   "),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "empty",
                "user",
                "system",
                "[]",
                () -> { },
                List.of("sceneCode"),
                text -> text == null ? null : text.trim(),
                text -> text != null && !text.isBlank()
        ).join();

        assertNull(extractedText);
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

    private static final class SnapshotAwareAiClient implements AiClient {
        private final JsonNode snapshotRawResponse;
        private final AtomicInteger chatCalls = new AtomicInteger(0);
        private List<String> lastSnapshotRoles = List.of();
        private String lastSnapshotUserContent = "";

        private SnapshotAwareAiClient(JsonNode rawResponse) {
            this.snapshotRawResponse = rawResponse;
        }

        @Override
        public String chat(String userMessage, String systemPrompt) {
            chatCalls.incrementAndGet();
            return "{\"ok\":true}";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(
                List<NodeConversationContext.Message> snapshot,
                String toolsJson) {
            lastSnapshotRoles = snapshot.stream()
                    .map(NodeConversationContext.Message::getRole)
                    .collect(Collectors.toList());
            lastSnapshotUserContent = snapshot.get(snapshot.size() - 1).getContent();
            return CompletableFuture.completedFuture(snapshotRawResponse);
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            return CompletableFuture.completedFuture(snapshotRawResponse);
        }

        @Override
        public String providerName() {
            return "snapshot-aware";
        }
    }
}
