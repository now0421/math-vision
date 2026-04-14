package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorationNodeTest {

    @Test
    void explicitConceptModeUsesSingleDirectConceptGraphRequest() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(toolResponse("write_concept_graph", basicConceptGraphArguments()));

        Map<String, Object> ctx = buildContext(aiClient, createConfig(WorkflowConfig.INPUT_MODE_CONCEPT), "Circle angle theorem");

        new ExplorationNode().run(ctx);

        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        assertNotNull(graph);
        assertEquals(1, aiClient.toolCallCount);
        assertEquals(0, aiClient.chatCallCount);
        assertEquals(List.of(ToolSchemas.CONCEPT_GRAPH), aiClient.requestedTools);
        assertFalse(aiClient.requestedTools.contains(ToolSchemas.PREREQUISITES));
        assertFalse(aiClient.requestedTools.contains(ToolSchemas.FOUNDATION_CHECK));
        assertEquals(1, ctx.get(WorkflowKeys.EXPLORATION_API_CALLS));
    }

    @Test
    void conceptGraphPayloadIsNormalizedIntoKnowledgeGraph() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(toolResponse("write_concept_graph", typedConceptGraphArguments()));

        Map<String, Object> ctx = buildContext(aiClient, createConfig(WorkflowConfig.INPUT_MODE_CONCEPT), "Perpendicular bisector");

        new ExplorationNode().run(ctx);

        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        assertNotNull(graph);
        assertEquals("takeaway", graph.getRootNodeId());
        assertEquals(3, graph.countNodes());
        assertEquals(2, graph.countEdges());
        assertEquals(KnowledgeNode.NODE_TYPE_OBSERVATION, graph.getNode("symmetry").getNodeType());
        assertTrue(graph.getNode("symmetry").isFoundation());
        assertEquals(List.of("symmetry"), graph.getPrerequisiteEdges().get("build"));
        assertEquals(List.of("symmetry", "build", "takeaway"),
                graph.topologicalOrder().stream().map(KnowledgeNode::getId).collect(Collectors.toList()));
    }

    @Test
    void conceptGraphFallsBackToValidTerminalRootWhenRootIsInvalid() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(toolResponse("write_concept_graph", invalidRootConceptGraphArguments()));

        Map<String, Object> ctx = buildContext(aiClient, createConfig(WorkflowConfig.INPUT_MODE_CONCEPT), "Inscribed angle");

        new ExplorationNode().run(ctx);

        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        assertNotNull(graph);
        assertEquals("final_takeaway", graph.getRootNodeId());
        assertEquals(KnowledgeNode.NODE_TYPE_CONCLUSION, graph.getRootNode().getNodeType());
    }

    @Test
    void conceptGraphRecomputesInconsistentMinDepthValuesFromEdges() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(toolResponse("write_concept_graph", inconsistentDepthConceptGraphArguments()));

        Map<String, Object> ctx = buildContext(aiClient, createConfig(WorkflowConfig.INPUT_MODE_CONCEPT), "Reflection method");

        new ExplorationNode().run(ctx);

        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        assertNotNull(graph);
        assertEquals(0, graph.getNode("takeaway").getMinDepth());
        assertEquals(1, graph.getNode("bridge").getMinDepth());
        assertEquals(2, graph.getNode("intuition").getMinDepth());
    }

    @Test
    void conceptGraphFiltersDuplicateNodesSelfEdgesAndUnknownTargets() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(toolResponse("write_concept_graph", noisyConceptGraphArguments()));

        Map<String, Object> ctx = buildContext(aiClient, createConfig(WorkflowConfig.INPUT_MODE_CONCEPT), "Midpoint theorem");

        new ExplorationNode().run(ctx);

        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        assertNotNull(graph);
        assertEquals(2, graph.countNodes());
        assertEquals(1, graph.countEdges());
        assertEquals("Keep the first setup node", graph.getNode("setup").getStep());
        assertEquals(List.of("setup"), graph.getPrerequisiteEdges().get("takeaway"));
    }

    @Test
    void problemModeDirectGraphGenerationStillNormalizesRootAndDepths() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(toolResponse("write_problem_step_graph", problemGraphArguments()));

        Map<String, Object> ctx = buildContext(aiClient, createConfig(WorkflowConfig.INPUT_MODE_PROBLEM), "Given A and line l, find the shortest path.");

        new ExplorationNode().run(ctx);

        KnowledgeGraph graph = (KnowledgeGraph) ctx.get(WorkflowKeys.KNOWLEDGE_GRAPH);
        assertNotNull(graph);
        assertEquals(List.of(ToolSchemas.PROBLEM_GRAPH), aiClient.requestedTools);
        assertEquals("answer", graph.getRootNodeId());
        assertEquals(0, graph.getNode("answer").getMinDepth());
        assertEquals(1, graph.getNode("observe").getMinDepth());
        assertEquals(2, graph.getNode("prompt").getMinDepth());
        assertEquals(KnowledgeNode.NODE_TYPE_PROBLEM, graph.getNode("prompt").getNodeType());
    }

    private static Map<String, Object> buildContext(AiClient aiClient,
                                                    WorkflowConfig config,
                                                    String concept) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CONCEPT, concept);
        return ctx;
    }

    private static WorkflowConfig createConfig(String inputMode) {
        WorkflowConfig config = new WorkflowConfig();
        config.setInputMode(inputMode);
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_MANIM);
        config.setMaxDepth(4);
        config.setMinDepth(2);
        return config;
    }

    private static ObjectNode basicConceptGraphArguments() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("root_id", "takeaway");
        ArrayNode nodes = arguments.putArray("nodes");
        nodes.addObject()
                .put("id", "takeaway")
                .put("step", "State the final theorem takeaway")
                .put("reason", "This is the culminating beat.")
                .put("node_type", "conclusion")
                .put("min_depth", 0)
                .put("is_foundation", false);
        arguments.putObject("prerequisite_edges");
        return arguments;
    }

    private static ObjectNode typedConceptGraphArguments() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("root_id", "takeaway");
        ArrayNode nodes = arguments.putArray("nodes");
        nodes.addObject()
                .put("id", "takeaway")
                .put("step", "State the final perpendicular-bisector takeaway")
                .put("reason", "This closes the explanation.")
                .put("node_type", "conclusion")
                .put("min_depth", 8)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "build")
                .put("step", "Build equal-radius circles from the endpoints")
                .put("reason", "The construction creates the symmetry anchor.")
                .put("node_type", "construction")
                .put("min_depth", 0)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "symmetry")
                .put("step", "Observe that intersection points stay equally distant from both endpoints")
                .put("reason", "This is the learner-facing invariant.")
                .put("node_type", "observation")
                .put("min_depth", 0)
                .put("is_foundation", true);

        ObjectNode edges = arguments.putObject("prerequisite_edges");
        edges.putArray("takeaway").add("build");
        edges.putArray("build").add("symmetry");
        return arguments;
    }

    private static ObjectNode invalidRootConceptGraphArguments() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("root_id", "missing_root");
        ArrayNode nodes = arguments.putArray("nodes");
        nodes.addObject()
                .put("id", "final_takeaway")
                .put("step", "Summarize the inscribed-angle conclusion")
                .put("node_type", "conclusion")
                .put("min_depth", 4)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "setup")
                .put("step", "Mark the intercepted arc and the vertex")
                .put("node_type", "construction")
                .put("min_depth", 0)
                .put("is_foundation", false);

        ObjectNode edges = arguments.putObject("prerequisite_edges");
        edges.putArray("final_takeaway").add("setup");
        return arguments;
    }

    private static ObjectNode inconsistentDepthConceptGraphArguments() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("root_id", "takeaway");
        ArrayNode nodes = arguments.putArray("nodes");
        nodes.addObject()
                .put("id", "takeaway")
                .put("step", "Present the reflection shortcut")
                .put("node_type", "conclusion")
                .put("min_depth", 9)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "bridge")
                .put("step", "Turn the broken path into a straight path using reflection")
                .put("node_type", "derivation")
                .put("min_depth", 0)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "intuition")
                .put("step", "Notice why equal reflected segments preserve distance")
                .put("node_type", "observation")
                .put("min_depth", 0)
                .put("is_foundation", true);

        ObjectNode edges = arguments.putObject("prerequisite_edges");
        edges.putArray("takeaway").add("bridge");
        edges.putArray("bridge").add("intuition");
        return arguments;
    }

    private static ObjectNode noisyConceptGraphArguments() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("root_id", "takeaway");
        ArrayNode nodes = arguments.putArray("nodes");
        nodes.addObject()
                .put("id", "takeaway")
                .put("step", "Present the midpoint theorem takeaway")
                .put("node_type", "conclusion")
                .put("min_depth", 3)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "setup")
                .put("step", "Keep the first setup node")
                .put("node_type", "construction")
                .put("min_depth", 1)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "setup")
                .put("step", "Drop the duplicate setup node")
                .put("node_type", "observation")
                .put("min_depth", 0)
                .put("is_foundation", true);
        nodes.addObject()
                .put("id", " ")
                .put("step", "Blank id should disappear")
                .put("node_type", "concept")
                .put("min_depth", 0)
                .put("is_foundation", false);

        ObjectNode edges = arguments.putObject("prerequisite_edges");
        ArrayNode takeawayDeps = edges.putArray("takeaway");
        takeawayDeps.add("setup");
        takeawayDeps.add("setup");
        takeawayDeps.add("takeaway");
        takeawayDeps.add("missing");
        edges.putArray("setup").add("missing");
        return arguments;
    }

    private static ObjectNode problemGraphArguments() {
        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("root_id", "prompt");
        ArrayNode nodes = arguments.putArray("nodes");
        nodes.addObject()
                .put("id", "prompt")
                .put("step", "Restate the shortest-path problem")
                .put("node_type", "problem")
                .put("min_depth", 0)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "observe")
                .put("step", "Reflect the point across the river line")
                .put("node_type", "construction")
                .put("min_depth", 0)
                .put("is_foundation", false);
        nodes.addObject()
                .put("id", "answer")
                .put("step", "Conclude the straight reflected route is shortest")
                .put("node_type", "conclusion")
                .put("min_depth", 5)
                .put("is_foundation", false);

        ObjectNode edges = arguments.putObject("prerequisite_edges");
        edges.putArray("answer").add("observe");
        edges.putArray("observe").add("prompt");
        return arguments;
    }

    private static JsonNode toolResponse(String functionName, ObjectNode arguments) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", functionName);
        function.set("arguments", arguments);
        return response;
    }

    private static final class QueueAiClient implements AiClient {
        private final Deque<JsonNode> toolResponses = new ArrayDeque<>();
        private final List<String> requestedTools = new ArrayList<>();
        private int toolCallCount = 0;
        private int chatCallCount = 0;

        @Override
        public String chat(String userMessage, String systemPrompt) {
            chatCallCount++;
            return "{}";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            toolCallCount++;
            requestedTools.add(toolsJson);
            return CompletableFuture.completedFuture(toolResponses.removeFirst());
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
