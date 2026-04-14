package com.mathvision.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilsTest {

    @Test
    void extractTextFromResponseSupportsArrayContent() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode content = message.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", "{\"storyboard\":");
        content.addObject()
                .put("type", "text")
                .put("text", "{\"scenes\":[]}}");

        assertEquals("{\"storyboard\":\n{\"scenes\":[]}}", JsonUtils.extractTextFromResponse(response));
    }

    @Test
    void extractBestEffortTextFallsBackToReasoningContent() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode reasoning = message.putArray("reasoning_content");
        reasoning.addObject()
                .put("type", "text")
                .put("text", "{\"code\":\"print(1)\"}");

        assertEquals("{\"code\":\"print(1)\"}", JsonUtils.extractBestEffortTextFromResponse(response));
    }

    @Test
    void extractToolCallPayloadSupportsStructuredArguments() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_storyboard");
        function.putObject("arguments")
                .put("scene_count", 3)
                .putObject("storyboard")
                .putArray("scenes");

        JsonNode payload = JsonUtils.extractToolCallPayload(response);

        assertNotNull(payload);
        assertEquals(3, payload.get("scene_count").asInt());
        assertNotNull(payload.get("storyboard"));
    }

    @Test
    void extractCodeBlockRemovesUnterminatedPythonFence() {
        String fenced = String.join("\n",
                "```python",
                "from manim import *",
                "",
                "class DemoScene(Scene):",
                "    def construct(self):",
                "        pass");

        String extracted = JsonUtils.extractCodeBlock(fenced);

        assertTrue(extracted.startsWith("from manim import *"));
        assertTrue(extracted.contains("class DemoScene(Scene):"));
        assertEquals(-1, extracted.indexOf("```"));
    }

    @Test
    void extractCodeBlockSupportsUppercaseFenceLanguage() {
        String fenced = String.join("\n",
                "```Python",
                "from manim import *",
                "",
                "class DemoScene(Scene):",
                "    def construct(self):",
                "        pass",
                "```");

        String extracted = JsonUtils.extractCodeBlock(fenced);

        assertTrue(extracted.startsWith("from manim import *"));
        assertTrue(extracted.contains("def construct(self):"));
        assertEquals(-1, extracted.indexOf("```"));
    }
}
