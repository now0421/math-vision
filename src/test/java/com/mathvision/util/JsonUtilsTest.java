package com.mathvision.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void extractJsonObjectRepairsBareIdentifiersAcrossFields() {
        String malformed = "{\"scene_mode\":2d,\"behavior\":static,\"type\":create,\"kind\":text,"
                + "\"style\":[{\"role\":text,\"type\":plain_text,\"properties\":{\"color\":YELLOW}}]}";

        String extracted = JsonUtils.extractJsonObject(malformed);

        assertNotNull(extracted);
        JsonNode node = JsonUtils.parseTree(extracted);
        assertEquals("2d", node.get("scene_mode").asText());
        assertEquals("static", node.get("behavior").asText());
        assertEquals("create", node.get("type").asText());
        assertEquals("text", node.get("kind").asText());
        assertEquals("YELLOW", node.get("style").get(0).get("properties").get("color").asText());
    }

    @Test
    void parseTreeBestEffortRepairsSingleQuotesAndTrailingCommas() {
        String malformed = "{'scenes':[{'scene_id':'scene_1', 'title':'Intro',}], 'scene_mode':2d,}";

        JsonNode parsed = JsonUtils.parseTreeBestEffort(malformed);

        assertNotNull(parsed);
        assertEquals("scene_1", parsed.get("scenes").get(0).get("scene_id").asText());
        assertEquals("2d", parsed.get("scene_mode").asText());
    }

    @Test
    void parseTreeBestEffortReturnsNullForIrreparablePayload() {
        JsonNode parsed = JsonUtils.parseTreeBestEffort("{\"scenes\": [ this is not json ]");
        assertNull(parsed);
    }
}
