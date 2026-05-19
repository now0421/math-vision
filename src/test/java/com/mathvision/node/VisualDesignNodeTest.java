package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.Narrative;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VisualDesignNodeTest {

    @Test
    void visualDesignPromptUsesCompactKnowledgeGraphFields() {
        CapturingAiClient aiClient = new CapturingAiClient(validSceneDesignResponse());

        KnowledgeNode problem = new KnowledgeNode("problem", "State the reflection problem", 0);
        problem.setNodeType(KnowledgeNode.NODE_TYPE_PROBLEM);
        problem.setReason("Frame the opening beat.");
        problem.setEquations(java.util.List.of("AP = A'P"));
        problem.setDefinitions(Map.of("A'", "reflection of A across l"));

        KnowledgeNode currentStep = new KnowledgeNode("reflect", "Show the reflected point A'", 1);
        currentStep.setNodeType(KnowledgeNode.NODE_TYPE_CONSTRUCTION);
        currentStep.setReason("Reflection creates an equal-length path.");
        currentStep.setEquations(java.util.List.of("AP = A'P"));
        currentStep.setDefinitions(Map.of("A'", "reflection of A across l"));

        KnowledgeNode conclusion = new KnowledgeNode("answer", "Conclude the reflected route is shortest", 2);
        conclusion.setNodeType(KnowledgeNode.NODE_TYPE_CONCLUSION);
        conclusion.setReason("Close the explanation.");

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
                ),
                List.of(problem.getId(), currentStep.getId(), conclusion.getId())
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new VisualDesignNode().run(ctx);

        String currentPrompt = aiClient.findUserMessageContaining("- Step: Show the reflected point A'");
        assertNotNull(currentPrompt);
        assertTrue(currentPrompt.contains("AP = A'P"));
        assertTrue(currentPrompt.contains("A': reflection of A across l"));
        assertTrue(currentPrompt.contains("Global visual context:"));
        assertTrue(currentPrompt.contains("Direct downstream steps:\n- Conclude the reflected route is shortest"));
        assertFalse(currentPrompt.contains("gradually increase abstraction"));
        assertFalse(currentPrompt.contains("backend-neutral where possible"));

        // Narrative should be assembled in ctx
        assertNotNull(ctx.get(WorkflowKeys.NARRATIVE));
    }

    @Test
    void executionBatchesFreezeRegistrySnapshotsAndKeepMergeContinuity() {
        SnapshotRecordingAiClient aiClient = new SnapshotRecordingAiClient();

        KnowledgeNode start = node("start", "Start scene", KnowledgeNode.NODE_TYPE_PROBLEM);
        start.setEquations(List.of("eq-start"));
        KnowledgeNode left = node("left", "Left scene", KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode right = node("right", "Right scene", KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode merge = node("merge", "Merge scene", KnowledgeNode.NODE_TYPE_DERIVATION);

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
        config.setParallelVisualDesign(true);
        config.setMaxConcurrent(1);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        ctx.put(WorkflowKeys.CONFIG, config);

        new VisualDesignNode().run(ctx);

        String leftPrompt = aiClient.findUserMessageContaining("- Step: Left scene");
        String rightPrompt = aiClient.findUserMessageContaining("- Step: Right scene");
        String mergePrompt = aiClient.findUserMessageContaining("- Step: Merge scene");

        assertNotNull(leftPrompt);
        assertNotNull(rightPrompt);
        assertNotNull(mergePrompt);

        assertTrue(leftPrompt.contains("rootObj"));
        assertTrue(rightPrompt.contains("rootObj"));
        assertFalse(leftPrompt.contains("rightObj"));
        assertFalse(rightPrompt.contains("leftObj"));

        assertTrue(mergePrompt.contains("leftObj"));
        assertTrue(mergePrompt.contains("rightObj"));
        assertTrue(mergePrompt.contains("LEFT_COLOR"));
        assertTrue(mergePrompt.contains("RIGHT_COLOR"));
        assertTrue(mergePrompt.contains("Direct prerequisite steps:\n- Left scene\n- Right scene"));
        assertTrue(mergePrompt.contains("Merge scene guidance:"));

        Narrative narrative = (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
        assertNotNull(narrative);
        assertEquals(
                List.of("Scene start", "Scene left", "Scene right", "Scene merge"),
                narrative.getStoryboard().getScenes().stream()
                        .map(Narrative.StoryboardScene::getTitle)
                        .collect(Collectors.toList())
        );
    }

    @Test
    void visibleObjectRegistryRemovesExitedObjectsButKeepsFinalRegistry() {
        VisibilityLifecycleAiClient aiClient = new VisibilityLifecycleAiClient();

        KnowledgeNode introduce = node("introduce", "Introduce root object", KnowledgeNode.NODE_TYPE_PROBLEM);
        KnowledgeNode exit = node("exit", "Exit root object", KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode afterExit = node("afterExit", "After root exits", KnowledgeNode.NODE_TYPE_DERIVATION);

        KnowledgeGraph graph = graph(
                List.of(introduce, exit, afterExit),
                Map.of(
                        "introduce", List.of("exit"),
                        "exit", List.of("afterExit")
                ),
                List.of("introduce", "exit", "afterExit")
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new VisualDesignNode().run(ctx);

        String exitPrompt = aiClient.findUserMessageContaining("- Step: Exit root object");
        String afterExitPrompt = aiClient.findUserMessageContaining("- Step: After root exits");

        assertNotNull(exitPrompt);
        assertNotNull(afterExitPrompt);
        assertTrue(exitPrompt.contains("Currently visible object registry"));
        assertTrue(exitPrompt.contains("rootObj"));
        assertTrue(exitPrompt.contains("ROOT_VISIBLE_COLOR"));
        assertFalse(afterExitPrompt.contains("rootObj"));
        assertTrue(afterExitPrompt.contains("Currently visible object registry: empty"));

        Narrative narrative = (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
        assertNotNull(narrative);
        assertTrue(narrative.getStoryboard().getObjectRegistry().stream()
                .anyMatch(object -> "rootObj".equals(object.getId())));
    }

    @Test
    void visibleObjectRegistryAddsReenteredObjectsWithLatestStyle() {
        VisibilityLifecycleAiClient aiClient = new VisibilityLifecycleAiClient();

        KnowledgeNode introduce = node("introduce", "Introduce root object", KnowledgeNode.NODE_TYPE_PROBLEM);
        KnowledgeNode exit = node("exit", "Exit root object", KnowledgeNode.NODE_TYPE_OBSERVATION);
        KnowledgeNode reenter = node("reenter", "Reenter root object", KnowledgeNode.NODE_TYPE_DERIVATION);
        KnowledgeNode afterReenter = node("afterReenter", "After root reenters", KnowledgeNode.NODE_TYPE_CONCLUSION);

        KnowledgeGraph graph = graph(
                List.of(introduce, exit, reenter, afterReenter),
                Map.of(
                        "introduce", List.of("exit"),
                        "exit", List.of("reenter"),
                        "reenter", List.of("afterReenter")
                ),
                List.of("introduce", "exit", "reenter", "afterReenter")
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);

        new VisualDesignNode().run(ctx);

        String reenterPrompt = aiClient.findUserMessageContaining("- Step: Reenter root object");
        String afterReenterPrompt = aiClient.findUserMessageContaining("- Step: After root reenters");

        assertNotNull(reenterPrompt);
        assertNotNull(afterReenterPrompt);
        assertFalse(reenterPrompt.contains("rootObj"));
        assertTrue(afterReenterPrompt.contains("rootObj"));
        assertTrue(afterReenterPrompt.contains("ROOT_REENTERED_COLOR"));
    }

    private static KnowledgeNode node(String id, String step, String nodeType) {
        KnowledgeNode node = new KnowledgeNode(id, step, 0);
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

    private static JsonNode validSceneDesignResponse() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_scene_design");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        ObjectNode scene = arguments.putObject("scene");
        scene.put("scene_id", "scene_1");
        scene.put("title", "Reflection Setup");
        scene.put("goal", "Show the initial problem");
        scene.put("narration", "We begin with point A and line l.");
        scene.put("layout_goal", "Center layout");
        scene.put("scene_mode", "2d");
        scene.putArray("entering_objects");
        scene.putArray("actions");
        arguments.putArray("new_objects");
        function.put("arguments", JsonUtils.toJson(arguments));
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
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            String userMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(userMessage);
            lastUserMessage = userMessage;
            lastSystemPrompt = NodeConversationContext.getSystemContent(snapshot);
            return CompletableFuture.completedFuture("{\"layout\":\"fallback\",\"motion_plan\":\"fallback\",\"color_scheme\":\"fallback\"}");
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(List<NodeConversationContext.Message> snapshot,
                                                                 String toolsJson) {
            String userMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(userMessage);
            lastUserMessage = userMessage;
            lastSystemPrompt = NodeConversationContext.getSystemContent(snapshot);
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

        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            String userMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(userMessage);
            return CompletableFuture.completedFuture("{\"scene\":{\"title\":\"fallback\"},\"new_objects\":[]}");
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(
                List<com.mathvision.util.NodeConversationContext.Message> snapshot,
                String toolsJson) {
            String currentUserMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(currentUserMessage);
            return CompletableFuture.completedFuture(validSceneDesignResponseFor(currentUserMessage));
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
            return "snapshot-test";
        }
    }

    private static JsonNode validSceneDesignResponseFor(String userPrompt) {
        String suffix = "generic";
        String color = "GENERIC_COLOR";
        String objectId = "genericObj";
        if (userPrompt.contains("- Step: Merge scene")) {
            suffix = "merge";
            color = "MERGE_COLOR";
            objectId = "mergeObj";
        } else if (userPrompt.contains("- Step: Right scene")) {
            suffix = "right";
            color = "RIGHT_COLOR";
            objectId = "rightObj";
        } else if (userPrompt.contains("- Step: Left scene")) {
            suffix = "left";
            color = "LEFT_COLOR";
            objectId = "leftObj";
        } else if (userPrompt.contains("- Step: Start scene")) {
            suffix = "start";
            color = "ROOT_COLOR";
            objectId = "rootObj";
        }

        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_scene_design");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        ObjectNode scene = arguments.putObject("scene");
        scene.put("scene_id", "scene_" + suffix);
        scene.put("title", "Scene " + suffix);
        scene.put("goal", "Goal " + suffix);
        scene.put("narration", "Narration " + suffix);
        scene.put("layout_goal", "Layout " + suffix);
        scene.put("scene_mode", "2d");
        ArrayNode enteringObjects = scene.putArray("entering_objects");
        ObjectNode enteringObject = enteringObjects.addObject();
        enteringObject.put("id", objectId);
        enteringObject.put("kind", "point");
        enteringObject.put("content", objectId);
        enteringObject.put("placement", "center");
        enteringObject.putObject("style").put("color", color);
        scene.putArray("actions");

        ArrayNode newObjects = arguments.putArray("new_objects");
        ObjectNode newObject = newObjects.addObject();
        newObject.put("id", objectId);
        newObject.put("kind", "point");
        newObject.put("content", objectId);
        newObject.put("placement", "center");
        newObject.putArray("constraints");

        function.put("arguments", JsonUtils.toJson(arguments));
        return response;
    }

    private static final class VisibilityLifecycleAiClient implements AiClient {
        private final List<String> userMessages = new ArrayList<>();

        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            String userMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(userMessage);
            return CompletableFuture.completedFuture("{\"scene\":{\"title\":\"fallback\"},\"new_objects\":[]}");
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(List<NodeConversationContext.Message> snapshot,
                                                                 String toolsJson) {
            String userMessage = snapshot.get(snapshot.size() - 1).getContent();
            userMessages.add(userMessage);
            return CompletableFuture.completedFuture(visibilityLifecycleResponseFor(userMessage));
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
            return "visibility-lifecycle-test";
        }
    }

    private static JsonNode visibilityLifecycleResponseFor(String userPrompt) {
        if (userPrompt.contains("- Step: Exit root object")) {
            return sceneDesignResponse("exit", persistentObject("rootObj", "ROOT_EXIT_COLOR"), null, exitingObject("rootObj"), null);
        }
        if (userPrompt.contains("- Step: Reenter root object")) {
            return sceneDesignResponse("reenter", null, enteringObject("rootObj", "ROOT_REENTERED_COLOR"), null, null);
        }
        if (userPrompt.contains("- Step: After root exits")) {
            return sceneDesignResponse("after_exit", null, enteringObject("afterExitObj", "AFTER_EXIT_COLOR"), null, registryObject("afterExitObj"));
        }
        if (userPrompt.contains("- Step: After root reenters")) {
            return sceneDesignResponse("after_reenter", persistentObject("rootObj", "ROOT_REENTERED_COLOR"), null, null, null);
        }
        return sceneDesignResponse("introduce", null, enteringObject("rootObj", "ROOT_VISIBLE_COLOR"), null, registryObject("rootObj"));
    }

    private static JsonNode sceneDesignResponse(String suffix,
                                                ObjectNode persistentObject,
                                                ObjectNode enteringObject,
                                                ObjectNode exitingObject,
                                                ObjectNode newObject) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_scene_design");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        ObjectNode scene = arguments.putObject("scene");
        scene.put("scene_id", "scene_" + suffix);
        scene.put("title", "Scene " + suffix);
        scene.put("goal", "Goal " + suffix);
        scene.put("narration", "Narration " + suffix);
        scene.put("layout_goal", "Layout " + suffix);
        scene.put("scene_mode", "2d");
        ArrayNode enteringObjects = scene.putArray("entering_objects");
        if (enteringObject != null) {
            enteringObjects.add(enteringObject);
        }
        ArrayNode persistentObjects = scene.putArray("persistent_objects");
        if (persistentObject != null) {
            persistentObjects.add(persistentObject);
        }
        ArrayNode exitingObjects = scene.putArray("exiting_objects");
        if (exitingObject != null) {
            exitingObjects.add(exitingObject);
        }
        scene.putArray("actions");

        ArrayNode newObjects = arguments.putArray("new_objects");
        if (newObject != null) {
            newObjects.add(newObject);
        }
        function.put("arguments", JsonUtils.toJson(arguments));
        return response;
    }

    private static ObjectNode enteringObject(String id, String color) {
        ObjectNode object = JsonUtils.mapper().createObjectNode();
        object.put("id", id);
        object.putObject("style").put("color", color);
        ObjectNode placement = object.putObject("placement");
        placement.put("coordinate_space", "screen");
        placement.putObject("x").put("value", 0.0);
        placement.putObject("y").put("value", 0.0);
        return object;
    }

    private static ObjectNode persistentObject(String id, String color) {
        return enteringObject(id, color);
    }

    private static ObjectNode exitingObject(String id) {
        ObjectNode object = JsonUtils.mapper().createObjectNode();
        object.put("id", id);
        return object;
    }

    private static ObjectNode registryObject(String id) {
        ObjectNode object = JsonUtils.mapper().createObjectNode();
        object.put("id", id);
        object.put("kind", "point");
        object.put("content", id);
        object.putArray("constraints");
        return object;
    }
}
