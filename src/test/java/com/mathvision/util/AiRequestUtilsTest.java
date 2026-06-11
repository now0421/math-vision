package com.mathvision.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mathvision.model.AiError;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.AiToolCall;
import com.mathvision.node.support.NodeSupport;
import com.mathvision.service.AiClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRequestUtilsTest {

    @Test
    void usesCustomPlainTextParserForToolTextResponses() {
        FakeAiClient aiClient = new FakeAiClient(wrapTextResponse("problem"), "{\"ignored\":true}");

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "input mode",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.JsonRequestOptions.builder()
                        .plainTextParser(text -> {
                            ObjectNode payload = JsonUtils.mapper().createObjectNode();
                            payload.put("input_mode", text.trim());
                            return payload;
                        })
                        .build()
        ).join();

        assertEquals("problem", result.getPayload().get("input_mode").asText());
        assertEquals(0, aiClient.plainChatCalls.get());
    }

    @Test
    void jsonFallsBackToPlainChatWhenToolResponseHasNoJson() {
        FakeAiClient aiClient = new FakeAiClient(
                wrapTextResponse("not json at all"),
                "{\"ok\":true}"
        );

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "visual design",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.JsonRequestOptions.defaults()
        ).join();

        assertTrue(result.getPayload().get("ok").asBoolean());
        assertEquals(1, aiClient.plainChatCalls.get());
    }

    @Test
    void customValidatorRejectsToolPayloadAndFallsBackToPlainChat() {
        ObjectNode toolArguments = JsonUtils.mapper().createObjectNode();
        toolArguments.put("scene_name", "DemoScene");

        FakeAiClient aiClient = new FakeAiClient(
                wrapToolResponse(toolArguments),
                "{\"manimCode\":\"print('ok')\"}"
        );

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "code generation",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.JsonRequestOptions.builder()
                        .plainTextParser(JsonUtils::parseTree)
                        .payloadValidator(payload -> payload != null
                                && payload.has("manimCode")
                                && !payload.get("manimCode").asText("").isBlank())
                        .build()
        ).join();

        assertEquals("print('ok')", result.getPayload().get("manimCode").asText());
        assertEquals(1, aiClient.plainChatCalls.get());
    }

    @Test
    void explicitSnapshotRequestsReuseFrozenContextAndReturnTranscript() {
        SnapshotAwareAiClient aiClient = new SnapshotAwareAiClient(wrapToolResponse(
                JsonUtils.parseTree("{\"equations\":[\"x=1\"],\"definitions\":{}}")
        ));

        AiRequestUtils.JsonObjectResult response = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "math enrichment",
                NodeSupport.buildAiRequest(
                        List.of(
                                new NodeConversationContext.Message("system", "system prompt"),
                                new NodeConversationContext.Message("user", "older user"),
                                new NodeConversationContext.Message("assistant", "older assistant")
                        ),
                        1000,
                        "current user",
                        "[]"),
                AiRequestUtils.JsonRequestOptions.defaults()
        ).join();

        assertEquals("x=1", response.getPayload().get("equations").get(0).asText());
        assertTrue(response.getAssistantTranscript().contains("tool"));
        assertEquals(List.of("system", "user", "assistant", "user"), aiClient.lastSnapshotRoles);
        assertEquals("current user", aiClient.lastSnapshotUserContent);
    }

    @Test
    void jsonObjectResultIncludesPlainTextParseFailureReason() {
        FakeAiClient aiClient = new FakeAiClient(
                wrapTextResponse("```json\n{\"note\":\"C\\' is invalid json\"}\n```"),
                "```json\n{\"note\":\"C\\' is still invalid\"}\n```"
        );

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "placement-enrichment",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.JsonRequestOptions.defaults()
        ).join();

        assertNull(result.getPayload());
        assertTrue(result.getFailureReason().contains("JSON parse failed"));
        assertTrue(result.getFailureReason().contains("message.content code block JSON parse failed"));
        assertTrue(result.getFailureReason().contains("plain-text retry failed"));
        assertEquals(1, aiClient.plainChatCalls.get());
    }

    @Test
    void codePrefersConfiguredPayloadField() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("manimCode", "from manim import *\nclass MainScene(Scene):\n    pass");

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                new FakeAiClient(wrapToolResponse(arguments), "ignored"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                request(createContext("system"), "user", "[]"),
                codeOptions(List.of("manimCode"))
        ).join();

        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getCode());
        assertTrue(result.getAssistantTranscript().contains("[tool_call]"));
        assertTrue(result.getAssistantTranscript().contains("\"manimCode\""));
        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getPayload().get("manimCode").asText());
    }

    @Test
    void codeFallsBackToCodeBlockWhenPayloadFieldMissing() {
        String responseText = "```python\nfrom manim import *\nclass MainScene(Scene):\n    pass\n```";

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                new FakeAiClient(wrapTextResponse(responseText), "ignored"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                request(createContext("system"), "user", "[]"),
                codeOptions(List.of("manimCode"))
        ).join();

        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getCode());
    }

    @Test
    void textReturnsWholeResponseWhenNoToolPayloadFieldExists() {
        AiRequestUtils.TextResult result = AiRequestUtils.requestTextAsync(
                new FakeAiClient(wrapTextResponse("problem"), "ignored"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "classification",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.TextRequestOptions.builder()
                        .preferredPayloadFields(List.of("input_mode"))
                        .textExtractor(text -> text == null ? null : text.trim())
                        .textValidator(text -> text != null && !text.isBlank())
                        .build()
        ).join();

        assertEquals("problem", result.getText());
    }

    @Test
    void textReturnsFailureWhenNothingUsableIsFound() {
        AiRequestUtils.TextResult result = AiRequestUtils.requestTextAsync(
                new FakeAiClient(wrapTextResponse("   "), "   "),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "empty",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.TextRequestOptions.builder()
                        .preferredPayloadFields(List.of("sceneCode"))
                        .textExtractor(text -> text == null ? null : text.trim())
                        .textValidator(text -> text != null && !text.isBlank())
                        .build()
        ).join();

        assertNull(result.getText());
        assertTrue(result.getFailureReason().contains("message.content was empty"));
        assertTrue(result.getFailureReason().contains("plain-text retry failed"));
    }

    @Test
    void codeFallsBackToPlainChatWhenToolResponseHasNoUsableCode() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("scene_name", "DemoScene");

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                new FakeAiClient(
                        wrapToolResponse(arguments),
                        "```python\nfrom manim import *\nclass MainScene(Scene):\n    pass\n```"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                request(createContext("system"), "user", "[]"),
                codeOptions(List.of("manimCode"))
        ).join();

        assertEquals("from manim import *\nclass MainScene(Scene):\n    pass", result.getCode());
    }

    @Test
    void codeFailureReasonIdentifiesRejectedPayloadFieldAndContent() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("manimCode", "not a scene");

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                new FakeAiClient(wrapToolResponse(arguments), "also not a scene"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.CodeRequestOptions.builder()
                        .preferredPayloadFields(List.of("manimCode", "sceneCode"))
                        .codeExtractor(text -> text == null ? null : text.trim())
                        .codeValidator(text -> text != null && text.contains("class MainScene"))
                        .build()
        ).join();

        assertNull(result.getCode());
        assertTrue(result.getFailureReason().contains("Tool-call payload field 'manimCode'"));
        assertTrue(result.getFailureReason().contains("code validator rejected extracted code"));
        assertTrue(result.getFailureReason().contains("missing fields: sceneCode"));
        assertTrue(result.getFailureReason().contains("message.content code validator rejected extracted code"));
        assertTrue(result.getFailureReason().contains("plain-text retry failed"));
    }

    @Test
    void textFailureReasonIncludesExtractorException() {
        AiRequestUtils.TextResult result = AiRequestUtils.requestTextAsync(
                new FakeAiClient(wrapTextResponse("boom"), "boom again"),
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "classification",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.TextRequestOptions.builder()
                        .preferredPayloadFields(List.of("input_mode"))
                        .textExtractor(text -> {
                            throw new IllegalArgumentException("cannot normalize text");
                        })
                        .textValidator(text -> text != null && !text.isBlank())
                        .build()
        ).join();

        assertNull(result.getText());
        assertTrue(result.getFailureReason().contains("message.content text extractor failed"));
        assertTrue(result.getFailureReason().contains("cannot normalize text"));
        assertTrue(result.getFailureReason().contains("plain-text retry failed"));
    }

    @Test
    void jsonErrorResponseReturnsFailureReason() {
        FakeAiClient aiClient = new FakeAiClient(
                CompletableFuture.<JsonNode>failedFuture(new HttpTimeoutException("request timed out")),
                "{\"ok\":true}"
        );

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "placement-enrichment",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.JsonRequestOptions.defaults()
        ).join();

        assertNull(result.getPayload());
        assertTrue(result.getFailureReason().contains("request timed out"));
        assertEquals(0, aiClient.plainChatCalls.get());
    }

    @Test
    void codeErrorResponseReturnsFailureReason() {
        FakeAiClient aiClient = new FakeAiClient(
                CompletableFuture.<JsonNode>failedFuture(new HttpTimeoutException("request timed out")),
                "fallback text"
        );

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "codegen",
                request(createContext("system"), "user", "[]"),
                codeOptions(List.of("manimCode"))
        ).join();

        assertNull(result.getCode());
        assertTrue(result.getFailureReason().contains("request timed out"));
        assertEquals(0, aiClient.plainChatCalls.get());
    }

    @Test
    void jsonReturnsDetailedAiResponseError() {
        AiError error = detailedAiError();
        ResponseAiClient aiClient = new ResponseAiClient(AiResponse.failure(error));

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "json",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.JsonRequestOptions.defaults()
        ).join();

        assertNull(result.getPayload());
        assertSame(error, result.getError());
        assertDetailedFailureReason(result.getFailureReason());
        assertEquals(1, aiClient.calls.get());
    }

    @Test
    void codeReturnsDetailedAiResponseError() {
        AiError error = detailedAiError();
        ResponseAiClient aiClient = new ResponseAiClient(AiResponse.failure(error));

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "code",
                request(createContext("system"), "user", "[]"),
                codeOptions(List.of("manimCode"))
        ).join();

        assertNull(result.getCode());
        assertSame(error, result.getError());
        assertDetailedFailureReason(result.getFailureReason());
        assertEquals(1, aiClient.calls.get());
    }

    @Test
    void textReturnsDetailedAiResponseError() {
        AiError error = detailedAiError();
        ResponseAiClient aiClient = new ResponseAiClient(AiResponse.failure(error));

        AiRequestUtils.TextResult result = AiRequestUtils.requestTextAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "text",
                request(createContext("system"), "user", "[]"),
                AiRequestUtils.TextRequestOptions.defaults()
        ).join();

        assertNull(result.getText());
        assertSame(error, result.getError());
        assertDetailedFailureReason(result.getFailureReason());
        assertEquals(1, aiClient.calls.get());
    }

    @Test
    void jsonSuccessDoesNotReturnAiResponseError() {
        AiError error = detailedAiError();
        ResponseAiClient aiClient = new ResponseAiClient(responseWithError(
                AiResponse.success("{\"ok\":true}", List.of(), null),
                error));

        AiRequestUtils.JsonObjectResult result = AiRequestUtils.requestJsonAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "json",
                request(createContext("system"), "user", null),
                AiRequestUtils.JsonRequestOptions.defaults()
        ).join();

        assertTrue(result.getPayload().get("ok").asBoolean());
        assertNull(result.getError());
        assertEquals("", result.getFailureReason());
    }

    @Test
    void codeSuccessDoesNotReturnAiResponseError() {
        AiError error = detailedAiError();
        ResponseAiClient aiClient = new ResponseAiClient(responseWithError(
                AiResponse.success("```python\nclass MainScene(Scene):\n    pass\n```", List.of(), null),
                error));

        AiRequestUtils.CodeResult result = AiRequestUtils.requestCodeAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "code",
                request(createContext("system"), "user", null),
                codeOptions(List.of("manimCode"))
        ).join();

        assertTrue(result.getCode().contains("class MainScene"));
        assertNull(result.getError());
        assertEquals("", result.getFailureReason());
    }

    @Test
    void textSuccessDoesNotReturnAiResponseError() {
        AiError error = detailedAiError();
        ResponseAiClient aiClient = new ResponseAiClient(responseWithError(
                AiResponse.success("problem", List.of(), null),
                error));

        AiRequestUtils.TextResult result = AiRequestUtils.requestTextAsync(
                aiClient,
                LoggerFactory.getLogger(AiRequestUtilsTest.class),
                "text",
                request(createContext("system"), "user", null),
                AiRequestUtils.TextRequestOptions.defaults()
        ).join();

        assertEquals("problem", result.getText());
        assertNull(result.getError());
        assertEquals("", result.getFailureReason());
    }

    private static AiRequestUtils.CodeRequestOptions codeOptions(List<String> fields) {
        return AiRequestUtils.CodeRequestOptions.builder()
                .preferredPayloadFields(fields)
                .codeExtractor(text -> text == null ? null : text.trim())
                .codeValidator(text -> text != null && !text.isBlank())
                .build();
    }

    private static AiRequest request(NodeConversationContext context, String userPrompt, String toolsJson) {
        return NodeSupport.buildAiRequest(context, userPrompt, toolsJson);
    }

    private static AiError detailedAiError() {
        AiError error = new AiError();
        error.setProvider("openai");
        error.setModel("gpt-test");
        error.setHttpStatus(429);
        error.setRequestId("req-123");
        error.setMessage("rate limited");
        error.setExceptionClass("com.example.RateLimitException");
        error.setRateLimited(true);
        error.setTransientFailure(true);
        error.setResponseBody("{\"error\":\"slow down\"}");
        return error;
    }

    private static AiResponse responseWithError(AiResponse response, AiError error) {
        response.setError(error);
        return response;
    }

    private static void assertDetailedFailureReason(String failureReason) {
        assertTrue(failureReason.contains("AI request failed"));
        assertTrue(failureReason.contains("provider=openai"));
        assertTrue(failureReason.contains("model=gpt-test"));
        assertTrue(failureReason.contains("http_status=429"));
        assertTrue(failureReason.contains("request_id=req-123"));
        assertTrue(failureReason.contains("message=rate limited"));
        assertTrue(failureReason.contains("exception=com.example.RateLimitException"));
        assertTrue(failureReason.contains("rate_limited=true"));
        assertTrue(failureReason.contains("transient=true"));
        assertTrue(failureReason.contains("response_body={\"error\":\"slow down\"}"));
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

    private static NodeConversationContext createContext(String systemPrompt) {
        NodeConversationContext context = new NodeConversationContext(1000);
        context.setSystemMessage(systemPrompt);
        return context;
    }

    private static final class FakeAiClient implements AiClient {
        private final JsonNode rawResponse;
        private final CompletableFuture<JsonNode> rawResponseFuture;
        private final String plainChatResponse;
        private final AtomicInteger plainChatCalls = new AtomicInteger(0);

        private FakeAiClient(JsonNode rawResponse, String plainChatResponse) {
            this.rawResponse = rawResponse;
            this.rawResponseFuture = null;
            this.plainChatResponse = plainChatResponse;
        }

        private FakeAiClient(CompletableFuture<JsonNode> rawResponseFuture, String plainChatResponse) {
            this.rawResponse = null;
            this.rawResponseFuture = rawResponseFuture;
            this.plainChatResponse = plainChatResponse;
        }

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            if (request.getToolsJson() != null && !request.getToolsJson().isBlank()) {
                return rawResponseFuture != null
                        ? rawResponseFuture.thenApply(AiRequestUtilsTest::responseFromRaw)
                        : CompletableFuture.completedFuture(responseFromRaw(rawResponse));
            }
            plainChatCalls.incrementAndGet();
            return CompletableFuture.completedFuture(AiResponse.success(plainChatResponse, List.of(), null));
        }
    }

    private static final class SnapshotAwareAiClient implements AiClient {
        private final JsonNode snapshotRawResponse;
        private List<String> lastSnapshotRoles = List.of();
        private String lastSnapshotUserContent = "";

        private SnapshotAwareAiClient(JsonNode rawResponse) {
            this.snapshotRawResponse = rawResponse;
        }

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            if (request.getToolsJson() == null || request.getToolsJson().isBlank()) {
                AiError error = AiError.fromException(new AssertionError("toolsJson was expected"));
                return CompletableFuture.completedFuture(AiResponse.failure(error));
            }
            lastSnapshotRoles = request.getMessages().stream()
                    .map(AiMessage::getRole)
                    .collect(Collectors.toList());
            lastSnapshotUserContent = textContent(request.getMessages().get(request.getMessages().size() - 1));
            return CompletableFuture.completedFuture(responseFromRaw(snapshotRawResponse));
        }
    }

    private static final class ResponseAiClient implements AiClient {
        private final AiResponse response;
        private final AtomicInteger calls = new AtomicInteger(0);

        private ResponseAiClient(AiResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response);
        }
    }

    private static AiResponse responseFromRaw(JsonNode raw) {
        List<AiToolCall> toolCalls = new ArrayList<>();
        JsonNode payload = JsonUtils.extractToolCallPayload(raw);
        String toolName = JsonUtils.extractToolCallName(raw);
        if (payload != null || (toolName != null && !toolName.isBlank())) {
            AiToolCall toolCall = new AiToolCall();
            toolCall.setName(toolName);
            toolCall.setArguments(payload);
            toolCall.setArgumentsText(payload != null ? payload.toString() : "");
            toolCall.setRaw(raw);
            toolCalls.add(toolCall);
        }
        return AiResponse.success(JsonUtils.extractBestEffortTextFromResponse(raw), toolCalls, raw);
    }

    private static String textContent(AiMessage message) {
        if (message == null || message.getParts() == null) {
            return "";
        }
        return message.getParts().stream()
                .filter(part -> "text".equals(part.getType()))
                .map(part -> part.getText() != null ? part.getText() : "")
                .collect(Collectors.joining("\n"));
    }
}
