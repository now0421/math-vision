package com.mathvision.node;

import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualDesignNodeTest {

    @Test
    void visualDesignPromptUsesCompactKnowledgeGraphFields() {
        CapturingAiClient aiClient = new CapturingAiClient(validVisualDesignResponse());

        KnowledgeNode problem = new KnowledgeNode("problem", "State the reflection problem", 0, false);
        problem.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        problem.setReason("Frame the opening beat.");
        problem.setEquations(java.util.List.of("AP = A'P"));
        problem.setDefinitions(Map.of("A'", "reflection of A across l"));

        KnowledgeNode currentStep = new KnowledgeNode("reflect", "Show the reflected point A'", 1, false);
        currentStep.setNodeType(KnowledgeNode.NODE_TYPE_CONSTRUCTION);
        currentStep.setReason("Reflection creates an equal-length path.");
        currentStep.setEquations(java.util.List.of("AP = A'P"));
        currentStep.setDefinitions(Map.of("A'", "reflection of A across l"));

        KnowledgeNode conclusion = new KnowledgeNode("answer", "Conclude the reflected route is shortest", 2, false);
        conclusion.setNodeType(KnowledgeNode.NODE_TYPE_CONCLUSION);
        conclusion.setReason("Close the explanation.");

        Map<String, Object> prerequisiteVisualSpec = new LinkedHashMap<>();
        prerequisiteVisualSpec.put("layout", "Keep l centered before introducing the mirror point.");
        prerequisiteVisualSpec.put("motion_plan", "Show the base problem setup.");
        problem.setVisualSpec(prerequisiteVisualSpec);

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(problem.getId(), problem);
        nodes.put(currentStep.getId(), currentStep);
        nodes.put(conclusion.getId(), conclusion);

        KnowledgeGraph graph = new KnowledgeGraph(
                problem.getId(),
                "Given a point A and line l, construct the reflected point",
                nodes,
                Map.of(
                        problem.getId(), List.of(currentStep.getId()),
                        currentStep.getId(), List.of(conclusion.getId())
                )
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new VisualDesignNode().run(ctx);

        String currentPrompt = aiClient.findUserMessageContaining("- Step: Show the reflected point A'");
        assertNotNull(currentPrompt);
        assertTrue(currentPrompt.contains("Equations:"));
        assertTrue(currentPrompt.contains("Definitions:"));
        assertTrue(currentPrompt.contains("AP = A'P"));
        assertTrue(currentPrompt.contains("A': reflection of A across l"));
        assertTrue(currentPrompt.contains("Global visual context:"));
        assertTrue(currentPrompt.contains("Direct downstream steps:\n- Conclude the reflected route is shortest"));
        assertTrue(currentPrompt.contains("Prerequisite visual specs already chosen:\n- State the reflection problem"));
        assertFalse(currentPrompt.contains("- Node type:"));
        assertFalse(currentPrompt.contains("- Depth:"));
        assertFalse(currentPrompt.contains("- Reason from Stage 0:"));
        assertFalse(currentPrompt.contains("gradually increase abstraction"));
        assertFalse(currentPrompt.contains("backend-neutral where possible"));

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
        private final List<String> userMessages = new ArrayList<>();
        private String lastUserMessage;
        private String lastSystemPrompt;

        private CapturingAiClient(JsonNode rawResponse) {
            this.rawResponse = rawResponse;
        }

        @Override
        public String chat(String userMessage, String systemPrompt) {
            userMessages.add(userMessage);
            lastUserMessage = userMessage;
            lastSystemPrompt = systemPrompt;
            return "{\"layout\":\"fallback\",\"motion_plan\":\"fallback\",\"color_scheme\":\"fallback\"}";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            userMessages.add(userMessage);
            lastUserMessage = userMessage;
            lastSystemPrompt = systemPrompt;
            return CompletableFuture.completedFuture(rawResponse);
        }

        private String findUserMessageContaining(String snippet) {
            for (String userMessage : userMessages) {
                if (userMessage != null && userMessage.contains(snippet)) {
                    return userMessage;
                }
            }
            return null;
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
