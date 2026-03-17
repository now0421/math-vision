package com.automanim.node;

import com.automanim.config.ModelCatalogConfig;
import com.automanim.config.ModelConfig;
import com.automanim.config.WorkflowConfig;
import com.automanim.model.KnowledgeGraph;
import com.automanim.model.KnowledgeNode;
import com.automanim.model.Narrative;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeNodeTest {

    @Test
    void throwsWhenNoValidStoryboardIsGenerated() {
        SequentialAiClient aiClient = new SequentialAiClient(List.of(
                wrapTextResponse("not a storyboard"),
                wrapTextResponse("still not a storyboard")
        ));
        Map<String, Object> ctx = buildContext(aiClient);

        RuntimeException error = assertThrows(RuntimeException.class, () -> new NarrativeNode().run(ctx));

        assertTrue(error.getMessage().contains("valid storyboard"));
        assertEquals(2, aiClient.toolRequestCount.get());
        assertEquals(0, aiClient.chatCallCount.get());
    }

    @Test
    void retriesWholeNodeThroughPocketFlowAndSucceedsOnSecondAttempt() {
        SequentialAiClient aiClient = new SequentialAiClient(List.of(
                new RuntimeException("temporary provider failure"),
                validStoryboardResponse()
        ));
        Map<String, Object> ctx = buildContext(aiClient);

        new NarrativeNode().run(ctx);

        Narrative narrative = (Narrative) ctx.get(WorkflowKeys.NARRATIVE);
        assertNotNull(narrative);
        assertTrue(narrative.hasStoryboard());
        assertEquals(1, narrative.getSceneCount());
        assertEquals(2, aiClient.toolRequestCount.get());
        assertEquals(0, aiClient.chatCallCount.get());
    }

    private static Map<String, Object> buildContext(AiClient aiClient) {
        KnowledgeNode root = new KnowledgeNode("root", "Target concept", 0, false);
        root.setDescription("Introduce the target concept with one clear visual.");

        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        nodes.put(root.getId(), root);

        KnowledgeGraph graph = new KnowledgeGraph(
                root.getId(),
                "Target concept",
                nodes,
                Collections.emptyMap()
        );

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, createWorkflowConfig());
        ctx.put(WorkflowKeys.KNOWLEDGE_GRAPH, graph);
        return ctx;
    }

    private static WorkflowConfig createWorkflowConfig() {
        WorkflowConfig config = new WorkflowConfig();
        config.setModel("test-model");
        config.setInputMode(WorkflowConfig.INPUT_MODE_CONCEPT);

        ModelConfig model = new ModelConfig();
        model.setModel("test-model");
        model.setProvider("openai");
        model.setApiKeyEnv("TEST_API_KEY");
        model.setBaseUrl("https://example.com");
        model.setMaxInputTokens(4096);
        model.setMaxOutputTokens(1024);

        ModelCatalogConfig catalog = new ModelCatalogConfig();
        Map<String, ModelConfig> models = new LinkedHashMap<>();
        models.put("test-model", model);
        catalog.setModels(models);

        return config.resolve(catalog);
    }

    private static JsonNode wrapTextResponse(String text) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("content", text);
        return response;
    }

    private static JsonNode validStoryboardResponse() {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_storyboard");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        ObjectNode storyboard = arguments.putObject("storyboard");
        storyboard.put("hook", "Start from one clear question.");
        storyboard.put("summary", "Explain the target concept in one scene.");
        storyboard.put("continuity_plan", "Keep one stable layout.");
        storyboard.putArray("global_visual_rules").add("Keep the main diagram centered.");

        ObjectNode scene = storyboard.putArray("scenes").addObject();
        scene.put("scene_id", "scene_1");
        scene.put("title", "Overview");
        scene.put("goal", "Introduce the target concept.");
        scene.put("narration", "Show the main idea with one stable visual.");
        scene.put("duration_seconds", 8);
        scene.put("camera_anchor", "center");
        scene.put("layout_goal", "Keep the visual centered.");
        scene.put("safe_area_plan", "Stay inside the safe frame.");
        scene.putArray("concept_refs").add("Target concept");

        ObjectNode enteringObject = scene.putArray("entering_objects").addObject();
        enteringObject.put("id", "main_visual");
        enteringObject.put("kind", "visual");
        enteringObject.put("content", "main concept diagram");
        enteringObject.put("placement", "center");

        scene.putArray("persistent_objects").add("main_visual");
        scene.putArray("exiting_objects");

        ObjectNode action = scene.putArray("actions").addObject();
        action.put("order", 1);
        action.put("type", "create");
        action.putArray("targets").add("main_visual");
        action.put("description", "Create the main diagram.");

        scene.putArray("notes_for_codegen").add("Preserve the same object for later scenes.");

        arguments.put("scene_count", 1);
        arguments.put("estimated_duration", 8);
        function.set("arguments", arguments);
        return response;
    }

    private static final class SequentialAiClient implements AiClient {
        private final List<Object> toolResponses;
        private final AtomicInteger toolRequestCount = new AtomicInteger(0);
        private final AtomicInteger chatCallCount = new AtomicInteger(0);

        private SequentialAiClient(List<Object> toolResponses) {
            this.toolResponses = toolResponses;
        }

        @Override
        public String chat(String userMessage, String systemPrompt) {
            chatCallCount.incrementAndGet();
            return "";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            int index = toolRequestCount.getAndIncrement();
            Object response = index < toolResponses.size()
                    ? toolResponses.get(index)
                    : toolResponses.get(toolResponses.size() - 1);

            if (response instanceof RuntimeException) {
                return CompletableFuture.failedFuture((RuntimeException) response);
            }
            return CompletableFuture.completedFuture((JsonNode) response);
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
