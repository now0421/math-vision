package com.automanim.node;

import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualDesignNodeTest {

    @Test
    void visualDesignPromptUsesCompactKnowledgeGraphFields() {
        CapturingAiClient aiClient = new CapturingAiClient(validVisualDesignResponse());

        KnowledgeNode problem = new KnowledgeNode("problem", "Show the reflected point A'", 0, false);
        problem.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        problem.setReason("Reflection creates an equal-length path.");
        problem.setEquations(java.util.List.of("AP = A'P"));
        problem.setDefinitions(Map.of("A'", "reflection of A across l"));

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(problem.getId(), problem);

        KnowledgeGraph graph = new KnowledgeGraph(
                problem.getId(),
                "Given a point A and line l, construct the reflected point",
                nodes,
                Map.of()
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new VisualDesignNode().run(ctx);

        assertNotNull(aiClient.lastUserMessage);
        assertTrue(aiClient.lastUserMessage.contains("- Step: Show the reflected point A'"));
        assertTrue(aiClient.lastUserMessage.contains("Equations:"));
        assertTrue(aiClient.lastUserMessage.contains("Definitions:"));
        assertTrue(aiClient.lastUserMessage.contains("AP = A'P"));
        assertTrue(aiClient.lastUserMessage.contains("A': reflection of A across l"));
        assertFalse(aiClient.lastUserMessage.contains("- Node type:"));
        assertFalse(aiClient.lastUserMessage.contains("- Depth:"));
        assertFalse(aiClient.lastUserMessage.contains("- Reason from Stage 0:"));

        assertNotNull(aiClient.lastSystemPrompt);
        assertFalse(aiClient.lastSystemPrompt.contains("Reflection creates an equal-length path."));
    }

    private static JsonNode validVisualDesignResponse() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_visual_design");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("layout", "Keep l centered and place A' below it.");
        arguments.put("motion_plan", "Reveal A', then emphasize the mirror relationship.");
        arguments.put("color_scheme", "Blue line, orange points.");
        function.set("arguments", arguments);
        return response;
    }

    private static final class CapturingAiClient implements AiClient {
        private final JsonNode rawResponse;
        private String lastUserMessage;
        private String lastSystemPrompt;

        private CapturingAiClient(JsonNode rawResponse) {
            this.rawResponse = rawResponse;
        }

        @Override
        public String chat(String userMessage, String systemPrompt) {
            lastUserMessage = userMessage;
            lastSystemPrompt = systemPrompt;
            return "{\"layout\":\"fallback\",\"motion_plan\":\"fallback\",\"color_scheme\":\"fallback\"}";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            lastUserMessage = userMessage;
            lastSystemPrompt = systemPrompt;
            return CompletableFuture.completedFuture(rawResponse);
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
