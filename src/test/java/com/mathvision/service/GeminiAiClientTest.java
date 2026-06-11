package com.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mathvision.config.ModelConfig;
import com.mathvision.model.AiContentPart;
import com.mathvision.model.AiMessage;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.AiToolCall;
import com.mathvision.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiAiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void convertsOpenAiFunctionToolsToGeminiNativeTools() {
        ArrayNode tools = GeminiAiClient.convertOpenAiFunctionTools("[" +
                "{\"type\":\"function\",\"function\":{" +
                "\"name\":\"write_payload\"," +
                "\"description\":\"Write a payload\"," +
                "\"parameters\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{\"value\":{\"type\":\"string\"}}," +
                "\"required\":[\"value\"]," +
                "\"additionalProperties\":false" +
                "}}}" +
                "]");

        assertEquals(1, tools.size());
        JsonNode declaration = tools.get(0).at("/functionDeclarations/0");
        assertEquals("write_payload", declaration.path("name").asText());
        assertEquals("Write a payload", declaration.path("description").asText());
        assertEquals("object", declaration.at("/parametersJsonSchema/type").asText());
        assertEquals("string", declaration.at("/parametersJsonSchema/properties/value/type").asText());
        assertEquals("value", declaration.at("/parametersJsonSchema/required/0").asText());
        assertFalse(declaration.at("/parametersJsonSchema/additionalProperties").asBoolean(true));
    }

    @Test
    void buildGenerateContentBodyAddsSingleToolConfig() {
        GeminiAiClient client = new GeminiAiClient(modelConfig(), "test-key");

        ObjectNode body = client.buildGenerateContentBody(AiRequest.withTools(List.of(
                AiMessage.system("system prompt"),
                AiMessage.user(List.of(AiContentPart.text("hello")))
        ), "[" +
                "{\"type\":\"function\",\"function\":{" +
                "\"name\":\"write_payload\"," +
                "\"parameters\":{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}" +
                "}}" +
                "]"));

        assertEquals("system prompt", body.at("/system_instruction/parts/0/text").asText());
        assertEquals("user", body.at("/contents/0/role").asText());
        assertEquals("hello", body.at("/contents/0/parts/0/text").asText());
        assertEquals("write_payload", body.at("/tools/0/functionDeclarations/0/name").asText());
        assertEquals("ANY", body.at("/toolConfig/functionCallingConfig/mode").asText());
        assertEquals("write_payload",
                body.at("/toolConfig/functionCallingConfig/allowedFunctionNames/0").asText());
    }

    @Test
    void buildGenerateContentBodyDoesNotForceMultipleTools() {
        GeminiAiClient client = new GeminiAiClient(modelConfig(), "test-key");

        ObjectNode body = client.buildGenerateContentBody(AiRequest.withTools(List.of(
                AiMessage.user(List.of(AiContentPart.text("hello")))
        ), "[" +
                "{\"type\":\"function\",\"function\":{\"name\":\"write_one\",\"parameters\":{\"type\":\"object\"}}}," +
                "{\"type\":\"function\",\"function\":{\"name\":\"write_two\",\"parameters\":{\"type\":\"object\"}}}" +
                "]"));

        assertEquals("AUTO", body.at("/toolConfig/functionCallingConfig/mode").asText());
        assertTrue(body.at("/toolConfig/functionCallingConfig/allowedFunctionNames").isMissingNode());
        assertEquals("write_one", body.at("/tools/0/functionDeclarations/0/name").asText());
        assertEquals("write_two", body.at("/tools/0/functionDeclarations/1/name").asText());
    }

    @Test
    void parsesGeminiFunctionCallResponse() throws Exception {
        JsonNode root = MAPPER.readTree("{"
                + "\"candidates\":[{"
                + "\"content\":{\"parts\":["
                + "{\"text\":\"Using a tool\"},"
                + "{\"functionCall\":{\"name\":\"write_payload\",\"args\":{\"value\":\"ok\"}}}"
                + "]}"
                + "}]"
                + "}");

        AiResponse response = GeminiAiClient.parseGenerateContentResponse(root);

        assertEquals("Using a tool", response.getContent());
        assertEquals(1, response.getToolCalls().size());
        AiToolCall call = response.getToolCalls().get(0);
        assertEquals("write_payload", call.getName());
        assertNotNull(call.getArguments());
        assertEquals("ok", call.getArguments().path("value").asText());
        assertEquals("{\"value\":\"ok\"}", call.getArgumentsText());
    }

    @Test
    void parsesSnakeCaseFunctionCallResponse() throws Exception {
        ObjectNode candidate = JsonUtils.mapper().createObjectNode();
        ArrayNode parts = candidate.putObject("content").putArray("parts");
        parts.addObject()
                .putObject("function_call")
                .put("name", "write_payload")
                .putObject("args")
                .put("value", "ok");

        List<AiToolCall> calls = GeminiAiClient.extractToolCalls(candidate);

        assertEquals(1, calls.size());
        assertEquals("write_payload", calls.get(0).getName());
        assertEquals("ok", calls.get(0).getArguments().path("value").asText());
    }

    private static ModelConfig modelConfig() {
        ModelConfig config = new ModelConfig();
        config.setModel("gemini-2.0-flash");
        config.setProvider("gemini");
        config.setApiKeyEnv("GEMINI_API_KEY");
        config.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/models");
        config.setTemperature(0.1);
        config.setMaxOutputTokens(256);
        return config;
    }
}
