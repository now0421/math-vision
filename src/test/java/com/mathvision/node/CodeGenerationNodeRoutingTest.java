package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.ToolSchemas;
import com.mathvision.service.AiClient;
import com.mathvision.support.AiClientTestSupport;
import com.mathvision.util.GeoGebraCodeUtils;
import com.mathvision.util.JsonUtils;
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
    void codegenPromptIncludesCoordinateBoundsImplementationContract() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(codegenResponse(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.wait(1)")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildStoryboardNarrativeWithCoordinateBounds());

        new CodeGenerationNode().run(ctx);

        assertNotNull(aiClient.lastUserMessage);
        assertTrue(aiClient.lastUserMessage.contains("Coordinate bounds implementation contract"));
        assertTrue(aiClient.lastUserMessage.contains("coordinate_bounds x=[-4, 4], y=[-2, 3]"));
        assertTrue(aiClient.lastUserMessage.contains("uniform x/y unit scale"));
        assertTrue(aiClient.lastUserMessage.contains("same uniform unit scale"));
        assertTrue(aiClient.lastUserMessage.contains("storyboard radius directly as raw Manim frame units"));
    }

    @Test
    void codegenNodeSystemPromptIncludesOpacityApiRule() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(codegenResponse(String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        dot = Dot()",
                "        dot.set_opacity(0.5)")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildNarrative());

        new CodeGenerationNode().run(ctx);

        assertNotNull(aiClient.lastSystemPrompt);
        assertTrue(aiClient.lastSystemPrompt.contains("Manim opacity API rules"));
        assertTrue(aiClient.lastSystemPrompt.contains("Do not pass `opacity=`"));
        assertTrue(aiClient.lastSystemPrompt.contains("Line(...)"));
        assertTrue(aiClient.lastSystemPrompt.contains("set_opacity(...)"));
        assertTrue(aiClient.lastSystemPrompt.indexOf("Manim opacity API rules")
                < aiClient.lastSystemPrompt.indexOf("Manim syntax reference manual:"));
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
        aiClient.toolResponses.add(codegenMetadataOnlyResponse("DemoScene", ""));
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

    @Test
    void perSceneGenerationUsesStaticSkeletonAndDoesNotRollScenePromptsIntoHistory() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(sceneCodeResponse("label = Text(\"one\")\nself.play(Write(label))"));
        aiClient.toolResponses.add(sceneCodeResponse("self.wait(1)"));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, new WorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildTwoSceneNarrativeWithRegistry());

        new CodeGenerationNode().run(ctx);

        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        assertNotNull(codeResult);
        assertEquals(2, codeResult.getToolCalls());
        assertTrue(codeResult.getGeneratedCode().contains("class MainScene(Scene):"));
        assertTrue(codeResult.getGeneratedCode().contains("self.objects = {}"));
        assertTrue(codeResult.getGeneratedCode().contains("def scene_1(self):"));
        assertTrue(codeResult.getGeneratedCode().contains("def scene_2(self):"));
        assertFalse(codeResult.getGeneratedCode().contains("def scene_1_intro"));
        assertFalse(codeResult.getGeneratedCode().contains("def scene_2_finish"));

        assertEquals(2, aiClient.userMessages.size());
        assertTrue(aiClient.toolsJsonHistory.stream().allMatch(ToolSchemas.SCENE_CODE::equals));
        assertFalse(aiClient.userMessages.get(0).contains("write_code_skeleton"));
        assertFalse(aiClient.userMessages.get(1).contains("label = Text(\"one\")"));
        assertFalse(aiClient.userMessages.get(1).contains("Object registry (structured JSON for this scene's objects)"));
        assertTrue(aiClient.systemPrompts.get(0).contains("Object registry (compact JSON"));
        assertTrue(aiClient.systemPrompts.get(0).contains("\"id\" : \"title_main\""));
    }

    @Test
    void enrichedRegistrySummaryExposesPlacementForAllObjects() {
        StoryboardObject fixedPoint = new StoryboardObject();
        fixedPoint.setId("A");
        fixedPoint.setKind("point");
        fixedPoint.setPlacement(placement(-3.0, 1.0));

        StoryboardObject segment = new StoryboardObject();
        segment.setId("ABprime");
        segment.setKind("segment");

        StoryboardObject line = new StoryboardObject();
        line.setId("l");
        line.setKind("line");

        StoryboardObject pmin = new StoryboardObject();
        pmin.setId("Pmin");
        pmin.setKind("point");
        pmin.setConstraints(List.of(constraint(
                "Pmin_intersection",
                "construction",
                "intersection_of",
                Map.of("point", "Pmin", "object_a", "ABprime", "object_b", "l"),
                Map.of(),
                "hard")));
        pmin.setPlacement(placement(0.6, -1.0));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put(fixedPoint.getId(), fixedPoint);
        registry.put(segment.getId(), segment);
        registry.put(line.getId(), line);
        registry.put(pmin.getId(), pmin);

        String summary = CodeGenerationNode.formatRegistrySummary(registry, 4);

        assertTrue(summary.contains("id=A, kind=point, content=, placement=absolute x=-3.0 y=1.0"));
        assertTrue(summary.contains("id=Pmin"));
        assertTrue(summary.contains("\"relation\":\"intersection_of\""));
        assertTrue(summary.contains("\"object_a\":\"ABprime\""));
        assertFalse(summary.contains("dependency_relation"));
        assertFalse(summary.contains("dependency_objects"));
        assertFalse(summary.contains("behavior="));
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

    private static Narrative buildStoryboardNarrativeWithCoordinateBounds() {
        Narrative narrative = buildStoryboardNarrative();
        Narrative.StoryboardCoordinateBounds bounds = new Narrative.StoryboardCoordinateBounds();
        bounds.setX(new Narrative.StoryboardCoordinateBoundsAxis(-4.0, 4.0));
        bounds.setY(new Narrative.StoryboardCoordinateBoundsAxis(-2.0, 3.0));
        bounds.setPadding(1.0);
        narrative.getStoryboard().setCoordinateBounds(bounds);
        return narrative;
    }

    private static Narrative buildTwoSceneNarrativeWithRegistry() {
        Narrative narrative = buildStoryboardNarrative();
        Storyboard storyboard = narrative.getStoryboard();
        StoryboardScene first = storyboard.getScenes().get(0);

        StoryboardScene second = new StoryboardScene();
        second.setSceneId("scene_2");
        second.setTitle("Finish");
        second.setGoal("Finish the idea.");
        second.setNarration("Pause on the result.");
        second.setPersistentObjects(first.getPersistentObjects());
        second.setExitingObjects(new ArrayList<>());
        StoryboardAction action = new StoryboardAction();
        action.setOrder(1);
        action.setType("wait");
        action.setDescription("Hold the title.");
        second.setActions(List.of(action));

        StoryboardObject registryObject = new StoryboardObject();
        registryObject.setId("title_main");
        registryObject.setKind("text");
        registryObject.setContent("Demo title");
        storyboard.setObjectRegistry(List.of(registryObject));
        storyboard.setScenes(List.of(first, second));
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
        titlePlacement.setPositioning(Narrative.StoryboardPlacement.POSITIONING_ABSOLUTE);
        Narrative.StoryboardPlacementAxis yAxis = new Narrative.StoryboardPlacementAxis();
        yAxis.setValue(3.0);
        titlePlacement.setY(yAxis);
        title.setPlacement(titlePlacement);
        Narrative.StoryboardStyle titleStyle = new Narrative.StoryboardStyle();
        titleStyle.setColor("#FFFFFF");
        titleStyle.setFontSize(28.0);
        title.setStyle(titleStyle);
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

    private static StoryboardPlacement placement(double x, double y) {
        StoryboardPlacement placement = new StoryboardPlacement();
        placement.setPositioning(StoryboardPlacement.POSITIONING_ABSOLUTE);
        StoryboardPlacementAxis xAxis = new StoryboardPlacementAxis();
        xAxis.setValue(x);
        StoryboardPlacementAxis yAxis = new StoryboardPlacementAxis();
        yAxis.setValue(y);
        placement.setX(xAxis);
        placement.setY(yAxis);
        return placement;
    }

    private static Narrative.StoryboardConstraint constraint(String id,
                                                             String domain,
                                                             String relation,
                                                             Map<String, Object> refs,
                                                             Map<String, Object> parameters,
                                                             String strength) {
        Narrative.StoryboardConstraint constraint = new Narrative.StoryboardConstraint();
        constraint.setId(id);
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength(strength);
        return constraint;
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

    private static JsonNode codegenMetadataOnlyResponse(String sceneName, String content) {
        ObjectNode response = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("content", content);
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

    private static JsonNode sceneCodeResponse(String code) {
        ObjectNode response = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_scene_code");

        ObjectNode arguments = com.mathvision.util.JsonUtils.mapper().createObjectNode();
        arguments.put("sceneCode", code);
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
        private final List<String> userMessages = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<String> toolsJsonHistory = new ArrayList<>();

        @Override
        public CompletableFuture<AiResponse> chatAsync(AiRequest request) {
            lastUserMessage = AiClientTestSupport.lastUserContent(request);
            lastSystemPrompt = AiClientTestSupport.systemContent(request);
            lastToolsJson = request.getToolsJson();
            userMessages.add(lastUserMessage);
            systemPrompts.add(lastSystemPrompt);
            toolsJsonHistory.add(lastToolsJson);
            if (lastToolsJson == null || lastToolsJson.isBlank()) {
                return CompletableFuture.completedFuture(
                        AiClientTestSupport.textResponse(chatResponses.removeFirst()));
            }
            if (toolResponses.isEmpty()) {
                return CompletableFuture.failedFuture(new RuntimeException("tools not queued"));
            }
            return CompletableFuture.completedFuture(
                    AiClientTestSupport.rawResponse(toolResponses.removeFirst()));
        }
    }
}
