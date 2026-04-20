package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
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
import java.util.ArrayList;
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
        KnowledgeNode problem = new KnowledgeNode("problem", "State the shortest-path problem", 0, false);
        problem.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        problem.setReason("Frame the opening beat.");

        KnowledgeNode currentStep = new KnowledgeNode("reflect", "Reflect point A across line l", 1, false);
        currentStep.setNodeType(KnowledgeNode.NODE_TYPE_CONSTRUCTION);
        currentStep.setReason("Use symmetry to compare path lengths.");

        KnowledgeNode conclusion = new KnowledgeNode("answer", "Conclude the reflected route is shortest", 2, false);
        conclusion.setNodeType(KnowledgeNode.NODE_TYPE_CONCLUSION);
        conclusion.setReason("Close with the final answer.");

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(problem.getId(), problem);
        nodes.put(currentStep.getId(), currentStep);
        nodes.put(conclusion.getId(), conclusion);

        KnowledgeGraph graph = new KnowledgeGraph(
                problem.getId(),
                "Given a point A and line l, find the reflected point",
                nodes,
                Map.of(
                        problem.getId(), List.of(currentStep.getId()),
                        currentStep.getId(), List.of(conclusion.getId())
                ),
                List.of(problem.getId(), currentStep.getId(), conclusion.getId())
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new MathEnrichmentNode().run(ctx);

        String currentPrompt = aiClient.findUserMessageContaining("- Step: Reflect point A across line l");
        assertNotNull(currentPrompt);
        assertTrue(currentPrompt.contains("Direct prerequisite steps:\n- State the shortest-path problem"));
        assertTrue(currentPrompt.contains("Direct downstream steps:\n- Conclude the reflected route is shortest"));
        // targetConcept should NOT be repeated in user prompt (already in system prompt via buildWorkflowPrefix)
        assertFalse(currentPrompt.contains("Target problem:"));
        assertFalse(currentPrompt.contains("- Node type:"));
        assertFalse(currentPrompt.contains("- Depth:"));
        assertFalse(currentPrompt.contains("- Reason from Stage 0:"));
        // solution chain moved to system prompt
        assertFalse(currentPrompt.contains("solution-step chain"));

        assertNotNull(aiClient.lastSystemPrompt);
        // Solution chain is now part of the system prompt
        assertTrue(aiClient.lastSystemPrompt.contains("Solution step chain"));
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

    @Test
    void executionBatchesFreezeConversationSnapshotsAndExposeMergeInputs() {
        SnapshotRecordingAiClient aiClient = new SnapshotRecordingAiClient();

        KnowledgeNode start = node("start", "Start the explanation", KnowledgeNode.NODE_TYPE_CONCEPT);
        KnowledgeNode left = node("left", "Left branch insight", KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode right = node("right", "Right branch insight", KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode merge = node("merge", "Merge the two insights", KnowledgeNode.NODE_TYPE_DERIVATION);

        KnowledgeGraph graph = graph(
                List.of(start, left, right, merge),
                Map.of(
                        "start", List.of("left", "right"),
                        "left", List.of("merge"),
                        "right", List.of("merge")
                ),
                List.of("start", "left", "right", "merge")
        );

        WorkflowConfig config = new WorkflowConfig();
        config.setParallelMathEnrichment(true);
        config.setMaxConcurrent(1);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        ctx.put(WorkflowKeys.CONFIG, config);

        new MathEnrichmentNode().run(ctx);

        String leftSnapshot = aiClient.findSnapshotContaining("- Step: Left branch insight");
        String rightSnapshot = aiClient.findSnapshotContaining("- Step: Right branch insight");
        String mergeSnapshot = aiClient.findSnapshotContaining("- Step: Merge the two insights");
        String mergePrompt = aiClient.findUserMessageContaining("- Step: Merge the two insights");

        assertNotNull(leftSnapshot);
        assertNotNull(rightSnapshot);
        assertNotNull(mergeSnapshot);
        assertNotNull(mergePrompt);

        assertTrue(leftSnapshot.contains("eq-start"));
        assertTrue(rightSnapshot.contains("eq-start"));
        assertFalse(leftSnapshot.contains("eq-right"));
        assertFalse(rightSnapshot.contains("eq-left"));

        assertTrue(mergeSnapshot.contains("Left branch insight"));
        assertTrue(mergeSnapshot.contains("Right branch insight"));
        assertTrue(mergePrompt.contains("Direct prerequisite steps:\n- Left branch insight\n- Right branch insight"));
        assertTrue(mergePrompt.contains("Merge node guidance:"));
    }

    private void applyContent(MathEnrichmentNode enrichmentNode,
                              KnowledgeNode node,
                              ObjectNode payload) throws Exception {
        Method method = MathEnrichmentNode.class.getDeclaredMethod(
                "applyContent", KnowledgeNode.class, com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        method.invoke(enrichmentNode, node, payload);
    }

    private static KnowledgeNode node(String id, String step, String nodeType) {
        KnowledgeNode node = new KnowledgeNode(id, step, 0, false);
        node.setNodeType(nodeType);
        return node;
    }

    private static KnowledgeGraph graph(List<KnowledgeNode> nodeList,
                                        Map<String, List<String>> nextEdges,
                                        List<String> teachingOrder) {
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        for (KnowledgeNode node : nodeList) {
            nodes.put(node.getId(), node);
        }
        return new KnowledgeGraph(
                teachingOrder.get(0),
                "Target concept",
                nodes,
                nextEdges,
                teachingOrder
        );
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
            return "{\"equations\":[],\"definitions\":{}}";
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

    private static final class SnapshotRecordingAiClient implements AiClient {
        private final List<String> userMessages = new ArrayList<>();
        private final Map<String, String> snapshotsByPrompt = new LinkedHashMap<>();

        @Override
        public String chat(String userMessage, String systemPrompt) {
            userMessages.add(userMessage);
            return "{\"equations\":[],\"definitions\":{}}";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(
                List<com.mathvision.util.NodeConversationContext.Message> snapshot,
                String toolsJson) {
            String currentUserMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(currentUserMessage);
            snapshotsByPrompt.put(currentUserMessage, snapshotSummary(snapshot));
            return CompletableFuture.completedFuture(validEnrichmentResponseFor(currentUserMessage));
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            userMessages.add(userMessage);
            return CompletableFuture.completedFuture(validEnrichmentResponseFor(userMessage));
        }

        private String findUserMessageContaining(String snippet) {
            for (String userMessage : userMessages) {
                if (userMessage != null && userMessage.contains(snippet)) {
                    return userMessage;
                }
            }
            return null;
        }

        private String findSnapshotContaining(String snippet) {
            for (Map.Entry<String, String> entry : snapshotsByPrompt.entrySet()) {
                if (entry.getKey().contains(snippet)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public String providerName() {
            return "snapshot-test";
        }
    }

    private static JsonNode validEnrichmentResponseFor(String userPrompt) {
        String suffix = "generic";
        if (userPrompt.contains("- Step: Merge the two insights")) {
            suffix = "merge";
        } else if (userPrompt.contains("- Step: Right branch insight")) {
            suffix = "right";
        } else if (userPrompt.contains("- Step: Left branch insight")) {
            suffix = "left";
        } else if (userPrompt.contains("- Step: Start the explanation")) {
            suffix = "start";
        }

        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_mathematical_content");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.putArray("equations").add("eq-" + suffix);
        arguments.putObject("definitions").put("symbol", "meaning-" + suffix);
        function.set("arguments", arguments);
        return response;
    }

    private static String snapshotSummary(List<com.mathvision.util.NodeConversationContext.Message> snapshot) {
        StringBuilder sb = new StringBuilder();
        for (com.mathvision.util.NodeConversationContext.Message message : snapshot) {
            sb.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        return sb.toString();
    }
}
