package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.the_pocket.PocketFlow;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGenerationNodeRoutingTest {

    @Test
    void doesNotRouteValidationFixFromGenerationStageAnymore() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(codegenResponse(String.join("\n",
                "from manim import *",
                "",
                "class DemoScene(Scene):",
                "    def construct(self):",
                "        self.bad = Text(\"bad\")")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildNarrative());

        CodeGenerationNode codeGeneration = new CodeGenerationNode();
        CodeFixNode codeFix = new CodeFixNode();
        codeGeneration.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(codeGeneration, WorkflowActions.RETRY_CODE_GENERATION);

        new PocketFlow.Flow<>(codeGeneration).run(ctx);

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        assertNotNull(codeResult);
        assertEquals("DemoScene", codeResult.getSceneName());
        assertTrue(codeResult.getGeneratedCode().contains("self.bad"));
        assertEquals(1, codeResult.getToolCalls());
    }

    @Test
    void codegenPromptUsesCompactStoryboardFocusedOnSceneExecution() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(codegenResponse(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        title = Text(\"ok\")",
                "        self.play(Write(title))")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildStoryboardNarrative());

        new CodeGenerationNode().run(ctx);

        assertNotNull(aiClient.lastUserMessage);
        assertTrue(aiClient.lastUserMessage.contains("\"scenes\""));
        assertTrue(aiClient.lastUserMessage.contains("\"entering_objects\""));
        assertTrue(aiClient.lastUserMessage.contains("\"actions\""));
        assertTrue(aiClient.lastUserMessage.contains("\"continuity_plan\""));
        assertTrue(aiClient.lastUserMessage.contains("\"safe_area_plan\""));
        assertTrue(aiClient.lastUserMessage.contains("\"goal\""));
        assertTrue(aiClient.lastUserMessage.contains("\"layout_goal\""));
        assertTrue(aiClient.lastUserMessage.contains("Scene class name: MainScene"));

        assertFalse(aiClient.lastUserMessage.contains("attached Manim syntax manual"));
        assertFalse(aiClient.lastUserMessage.contains("ASCII identifiers only"));
    }

    @Test
    void textOnlyToolResponseStillGeneratesCode() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(textResponse(String.join("\n",
                "```python",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        label = Text(\"ok\")",
                "        self.play(Write(label))",
                "```")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildNarrative());

        new CodeGenerationNode().run(ctx);

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        assertNotNull(codeResult);
        assertTrue(codeResult.getGeneratedCode().contains("class MainScene(Scene):"));
        assertTrue(codeResult.getGeneratedCode().contains("self.play(Write(label))"));
        assertEquals(1, codeResult.getToolCalls());
    }

    @Test
    void fallsBackWhenToolPayloadOmitsCode() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(codegenMetadataOnlyResponse("DemoScene"));
        aiClient.chatResponses.add(wrapCodeResponse(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        label = Text(\"fallback\")",
                "        self.play(Write(label))")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildNarrative());

        new CodeGenerationNode().run(ctx);

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        assertNotNull(codeResult);
        assertTrue(codeResult.getGeneratedCode().contains("fallback"));
        assertEquals("MainScene", codeResult.getSceneName());
        assertEquals(2, codeResult.getToolCalls());
    }

    @Test
    void geogebraTargetUsesGeoGebraToolingWithoutSceneClassSuffix() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(geogebraCodegenResponse(String.join("\n",
                "A = (0, 0)",
                "B = (4, 0)",
                "lineAB = Line(A, B)")));

        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.NARRATIVE, buildStoryboardNarrative());

        new CodeGenerationNode().run(ctx);

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        assertNotNull(codeResult);
        assertEquals(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA, codeResult.getOutputTarget());
        assertEquals("commands", codeResult.getArtifactFormat());
        assertTrue(codeResult.getGeneratedCode().contains("lineAB = Line(A, B)"));
        assertEquals(com.mathvision.prompt.ToolSchemas.GEOGEBRA_CODE, aiClient.lastToolsJson);
        assertTrue(aiClient.lastSystemPrompt.contains("GeoGebra"));
        assertTrue(aiClient.lastUserMessage.contains("Figure name: GeoGebraFigure"));
        assertFalse(aiClient.lastUserMessage.contains("Scene class name:"));
        assertFalse(aiClient.lastUserMessage.contains("attached GeoGebra syntax manual"));
    }

    @Test
    void geogebraPromptAllowsNativeMathNamesWithoutAsciiOnlyConflict() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(geogebraCodegenResponse(String.join("\n",
                "B' = (0, 0)",
                "P_{opt} = (1, 0)")));

        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.NARRATIVE, buildStoryboardNarrative());

        new CodeGenerationNode().run(ctx);

        assertNotNull(aiClient.lastSystemPrompt);
        assertNotNull(aiClient.lastUserMessage);
        assertTrue(aiClient.lastSystemPrompt.contains("`B'"));
        assertTrue(aiClient.lastSystemPrompt.contains("`P_{opt}`"));
        assertFalse(aiClient.lastUserMessage.contains("`B'`, `AB'`, and `P_{opt}` are allowed and preferred"));
        assertFalse(aiClient.lastUserMessage.contains("ASCII-safe"));
    }

    @Test
    void geogebraGenerationDoesNotTriggerStaticValidationFix() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(geogebraCodegenResponse(String.join("\n",
                "const A = (0, 0)",
                "B = (4, 0)",
                "lineAB = Line(A, B)")));

        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.NARRATIVE, buildStoryboardNarrative());

        CodeGenerationNode codeGeneration = new CodeGenerationNode();
        CodeFixNode codeFix = new CodeFixNode();
        codeGeneration.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(codeGeneration, WorkflowActions.RETRY_CODE_GENERATION);

        new PocketFlow.Flow<>(codeGeneration).run(ctx);

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        assertNotNull(codeResult);
        assertEquals(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA, codeResult.getOutputTarget());
        assertTrue(codeResult.getGeneratedCode().contains("const A = (0, 0)"));
        assertEquals(1, codeResult.getToolCalls());
        assertTrue(aiClient.lastSystemPrompt.contains("GeoGebra"));
    }

    private static Narrative buildNarrative() {
        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Demo concept");
        narrative.setTargetDescription("Demo description");
        narrative.setStoryboard(buildStoryboard());
        return narrative;
    }

    private static Narrative buildStoryboardNarrative() {
        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Demo concept");
        narrative.setTargetDescription("Demo description");
        narrative.setStoryboard(buildStoryboard());
        return narrative;
    }

    private static Storyboard buildStoryboard() {
        Storyboard storyboard = new Storyboard();
        storyboard.setContinuityPlan("Keep the same title object alive.");
        storyboard.setGlobalVisualRules(List.of("Keep the title in the safe area."));

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Intro");
        scene.setGoal("Introduce the main idea.");
        scene.setNarration("Write the title and pause.");
        scene.setDurationSeconds(6);
        scene.setSceneMode("2d");
        scene.setCameraAnchor("center");
        scene.setCameraPlan("Static 2D camera.");
        scene.setLayoutGoal("Place the title near the top.");
        scene.setSafeAreaPlan("Leave a top margin and keep all text centered.");
        scene.setScreenOverlayPlan("No fixed overlay needed.");
        scene.setStepRefs(List.of("problem"));

        StoryboardObject title = new StoryboardObject();
        title.setId("title_main");
        title.setKind("text");
        title.setContent("Demo title");
        Narrative.StoryboardPlacement titlePlacement = new Narrative.StoryboardPlacement();
        titlePlacement.setCoordinateSpace("screen");
        Narrative.StoryboardPlacementAxis yAxis = new Narrative.StoryboardPlacementAxis();
        yAxis.setValue(3.0);
        titlePlacement.setY(yAxis);
        title.setPlacement(titlePlacement);
        Narrative.StoryboardStyle titleStyle = new Narrative.StoryboardStyle();
        titleStyle.setRole("emphasis");
        titleStyle.setType("text");
        titleStyle.setProperties(Map.of(
                "color", "WHITE",
                "scale", 0.9
        ));
        title.setStyle(List.of(titleStyle));
        title.setSourceNode("problem");
        scene.setEnteringObjects(List.of(title));
        Narrative.StoryboardObject persistentTitle = new Narrative.StoryboardObject();
        persistentTitle.setId("title_main");
        scene.setPersistentObjects(List.of(persistentTitle));
        scene.setExitingObjects(new ArrayList<>());

        StoryboardAction action = new StoryboardAction();
        action.setOrder(1);
        action.setType("create");
        action.setTargets(List.of("title_main"));
        action.setDescription("Write the title.");
        scene.setActions(List.of(action));
        scene.setNotesForCodegen(List.of("Keep the title anchored near the top."));

        storyboard.setScenes(List.of(scene));
        return storyboard;
    }

    private static JsonNode codegenResponse(String code) {
        ObjectNode response = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_manim_code");

        ObjectNode arguments = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        arguments.put("manimCode", code);
        arguments.put("scene_name", "DemoScene");
        arguments.put("description", "demo");
        function.set("arguments", arguments);
        return response;
    }

    private static JsonNode codegenMetadataOnlyResponse(String sceneName) {
        ObjectNode response = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_manim_code");

        ObjectNode arguments = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        arguments.put("scene_name", sceneName);
        arguments.put("description", "metadata only");
        function.set("arguments", arguments);
        return response;
    }

    private static JsonNode geogebraCodegenResponse(String code) {
        ObjectNode response = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_geogebra_code");

        ObjectNode arguments = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        arguments.put("geogebraCode", code);
        arguments.put("figure_name", com.mathvision.util.GeoGebraCodeUtils.EXPECTED_FIGURE_NAME);
        arguments.put("description", "demo");
        arguments.put("artifact_format", "commands");
        function.set("arguments", arguments);
        return response;
    }

    private static String wrapCodeResponse(String code) {
        return "```python\n" + code + "\n```";
    }

    private static String wrapGeoGebraResponse(String code) {
        return "```geogebra\n" + code + "\n```";
    }

    private static JsonNode textResponse(String text) {
        ObjectNode response = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("content", text);
        return response;
    }

    private static final class QueueAiClient implements AiClient {
        private final Deque<JsonNode> toolResponses = new ArrayDeque<>();
        private final Deque<String> chatResponses = new ArrayDeque<>();
        private String lastUserMessage;
        private String lastSystemPrompt;
        private String lastToolsJson;

        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            lastUserMessage = snapshot.get(snapshot.size() - 1).getContent();
            lastSystemPrompt = NodeConversationContext.getSystemContent(snapshot);
            return CompletableFuture.completedFuture(chatResponses.removeFirst());
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(List<NodeConversationContext.Message> snapshot,
                                                                 String toolsJson) {
            lastUserMessage = snapshot.get(snapshot.size() - 1).getContent();
            lastSystemPrompt = NodeConversationContext.getSystemContent(snapshot);
            lastToolsJson = toolsJson;
            if (toolResponses.isEmpty()) {
                return CompletableFuture.failedFuture(new RuntimeException("tools not queued"));
            }
            return CompletableFuture.completedFuture(toolResponses.removeFirst());
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
