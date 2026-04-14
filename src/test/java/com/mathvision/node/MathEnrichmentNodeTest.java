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

import java.util.LinkedHashMap;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MathEnrichmentNodeTest {

    @Test
    void enrichmentPromptUsesCompactKnowledgeGraphFields() {
        CapturingAiClient aiClient = new CapturingAiClient(validEnrichmentResponse());
        KnowledgeNode problem = new KnowledgeNode("problem", "Reflect point A across line l", 0, false);
        problem.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        problem.setReason("Use symmetry to compare path lengths.");

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(problem.getId(), problem);

        KnowledgeGraph graph = new KnowledgeGraph(
                problem.getId(),
                "Given a point A and line l, find the reflected point",
                nodes,
                Map.of()
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new MathEnrichmentNode().run(ctx);

        assertNotNull(aiClient.lastUserMessage);
        assertTrue(aiClient.lastUserMessage.contains("- Step: Reflect point A across line l"));
        assertTrue(aiClient.lastUserMessage.contains("- Target problem: Given a point A and line l, find the reflected point"));
        assertFalse(aiClient.lastUserMessage.contains("- Node type:"));
        assertFalse(aiClient.lastUserMessage.contains("- Depth:"));
        assertFalse(aiClient.lastUserMessage.contains("- Reason from Stage 0:"));

        assertNotNull(aiClient.lastSystemPrompt);
        assertFalse(aiClient.lastSystemPrompt.contains("Use symmetry to compare path lengths."));
    }

    @Test
    void acceptsExplicitlyEmptyMathFieldsAsValidEnrichment() throws Exception {
        MathEnrichmentNode enrichmentNode = new MathEnrichmentNode();
        KnowledgeNode node = new KnowledgeNode("step_1", "Describe the setup", 0, false);

        ObjectNode payload = JsonUtils.mapper().createObjectNode();
        payload.putArray("equations");
        payload.putObject("definitions");
        payload.put("interpretation", "   ");
        payload.putArray("examples");

        applyContent(enrichmentNode, node, payload);

        assertEquals(List.of(), node.getEquations());
        assertEquals(Map.of(), node.getDefinitions());
        assertNull(node.getInterpretation());
        assertEquals(List.of(), node.getExamples());
        assertTrue(node.isEnriched());
    }

    @Test
    void trimsAndFiltersBlankMathEntries() throws Exception {
        MathEnrichmentNode enrichmentNode = new MathEnrichmentNode();
        KnowledgeNode node = new KnowledgeNode("step_2", "Solve for x", 0, false);

        ObjectNode payload = JsonUtils.mapper().createObjectNode();
        ArrayNode equations = payload.putArray("equations");
        equations.add("   ");
        equations.add(" x + 1 = 2 ");
        equations.add("");

        ObjectNode definitions = payload.putObject("definitions");
        definitions.put(" ", "ignored");
        definitions.put("x", " value of the unknown ");
        definitions.put("y", "   ");

        payload.put("interpretation", " isolate the variable ");

        ArrayNode examples = payload.putArray("examples");
        examples.add("");
        examples.add(" Substitute and check the result. ");

        applyContent(enrichmentNode, node, payload);

        assertEquals(List.of("x + 1 = 2"), node.getEquations());
        assertEquals(Map.of("x", "value of the unknown"), node.getDefinitions());
        assertEquals("isolate the variable", node.getInterpretation());
        assertEquals(List.of("Substitute and check the result."), node.getExamples());
        assertTrue(node.isEnriched());
    }

    private void applyContent(MathEnrichmentNode enrichmentNode,
                              KnowledgeNode node,
                              ObjectNode payload) throws Exception {
        Method method = MathEnrichmentNode.class.getDeclaredMethod(
                "applyContent", KnowledgeNode.class, com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        method.invoke(enrichmentNode, node, payload);
    }

    private static JsonNode validEnrichmentResponse() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_mathematical_content");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.putArray("equations").add("AP = A'P");
        arguments.putObject("definitions").put("A'", "reflection of A across l");
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
            return "{\"equations\":[],\"definitions\":{}}";
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
