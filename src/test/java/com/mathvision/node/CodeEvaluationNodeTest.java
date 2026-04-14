package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.service.AiClient;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.the_pocket.PocketFlow;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeEvaluationNodeTest {

    @Test
    void revisesCodeAndApprovesRenderWhenSecondReviewPasses() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(reviewResponse(false, 5, 4, 5, 6, 6,
                "Layout drifts and continuity is weak.",
                "Too many abrupt fades.",
                "Keep anchors stable."));
        aiClient.chatResponses.add(wrapCodeResponse(revisedCode()));
        aiClient.toolResponses.add(reviewResponse(true, 8, 8, 7, 3, 2,
                "Layout and continuity now look render-safe.",
                "Stable transform-based continuity.",
                "Approved for render."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode());
        PocketFlow.Flow<?> flow = evaluationFlow();

        flow.run(ctx);

        CodeEvaluationResult result =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);

        assertNotNull(result);
        assertTrue(result.isRevisionTriggered());
        assertTrue(result.isRevisedCodeApplied());
        assertTrue(result.isApprovedForRender());
        assertEquals(1, result.getRevisionAttempts());
        assertEquals(3, result.getToolCalls());
        assertTrue(codeResult.getGeneratedCode().contains("ReplacementTransform"));
        assertEquals("DemoScene", codeResult.getSceneName());
    }

    @Test
    void blocksRenderWhenRevisionStillFailsTheVisualGate() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(reviewResponse(false, 5, 4, 5, 6, 6,
                "Initial review fails.",
                "Crowded and discontinuous.",
                "Reduce clutter."));
        aiClient.chatResponses.add(wrapCodeResponse(revisedCode()));
        aiClient.toolResponses.add(reviewResponse(false, 6, 5, 5, 5, 5,
                "Revision improved but still not render-safe.",
                "Still too crowded.",
                "Split the scene further."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode());
        PocketFlow.Flow<?> flow = evaluationFlow();

        flow.run(ctx);

        CodeEvaluationResult result =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);

        assertNotNull(result);
        assertTrue(result.isRevisionTriggered());
        assertTrue(result.isRevisedCodeApplied());
        assertFalse(result.isApprovedForRender());
        assertTrue(result.getGateReason().contains("layout_score=")
                || result.getGateReason().contains("continuity_score=")
                || result.getGateReason().contains("clutter_risk="));
    }

    @Test
    void fallbackReviewFlagsMissingThreeDSceneWhenStoryboardAndCodeAreThreeDimensional() {
        Map<String, Object> ctx = buildContext(
                new FailingReviewAiClient(),
                String.join("\n",
                        "from manim import *",
                        "",
                        "class DemoScene(Scene):",
                        "    def construct(self):",
                        "        axes = ThreeDAxes()",
                        "        label = Text(\"3D\")",
                        "        self.add(axes, label)"),
                buildThreeDNarrative()
        );

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);

        assertFalse(result.isApprovedForRender());
        assertTrue(result.getInitialStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "three_d_scene_required".equals(finding.getRuleId())));
    }

    @Test
    void fallbackReviewFlagsUnfixedTextInMovingThreeDScene() {
        Map<String, Object> ctx = buildContext(
                new FailingReviewAiClient(),
                String.join("\n",
                        "from manim import *",
                        "",
                        "class DemoScene(ThreeDScene):",
                        "    def construct(self):",
                        "        axes = ThreeDAxes()",
                        "        title = Text(\"Orbit\")",
                        "        self.set_camera_orientation(phi=75 * DEGREES, theta=-45 * DEGREES)",
                        "        self.begin_ambient_camera_rotation(rate=0.2)",
                        "        self.add(axes, title)"),
                buildThreeDNarrative()
        );

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);

        assertTrue(result.getInitialStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "three_d_overlay_unfixed".equals(finding.getRuleId())));
    }

    @Test
    void reviewPromptUsesCompactStoryboardJson() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(reviewResponse(true, 8, 8, 8, 2, 1,
                "Looks good.",
                "None.",
                "Proceed to render."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode(), buildCompactReviewNarrative());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        node.exec(input);

        assertNotNull(aiClient.lastUserMessage);
        assertTrue(aiClient.lastUserMessage.contains("\"scenes\""));
        assertTrue(aiClient.lastUserMessage.contains("\"entering_objects\""));
        assertTrue(aiClient.lastUserMessage.contains("\"actions\""));
        assertTrue(aiClient.lastUserMessage.contains("\"safe_area_plan\""));
        assertTrue(aiClient.lastUserMessage.contains("\"goal\""));
        assertTrue(aiClient.lastUserMessage.contains("\"layout_goal\""));
        assertFalse(aiClient.lastUserMessage.contains("\"hook\""));
        assertFalse(aiClient.lastUserMessage.contains("\"summary\""));
    }

    @Test
    void evaluationFixRequestStoresCompactStoryboardJson() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(reviewResponse(false, 4, 4, 5, 7, 6,
                "Too crowded.",
                "Objects overlap.",
                "Reduce clutter."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode(), buildCompactReviewNarrative());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertEquals(WorkflowActions.FIX_CODE, action);
        com.mathvision.model.CodeFixRequest request =
                (com.mathvision.model.CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertNotNull(request.getStoryboardJson());
        assertTrue(request.getStoryboardJson().contains("\"scenes\""));
        assertTrue(request.getStoryboardJson().contains("\"entering_objects\""));
        assertTrue(request.getStoryboardJson().contains("\"goal\""));
        assertTrue(request.getStoryboardJson().contains("\"layout_goal\""));
        assertFalse(request.getStoryboardJson().contains("\"hook\""));
        assertFalse(request.getStoryboardJson().contains("\"summary\""));
    }

    private static Map<String, Object> buildContext(AiClient aiClient, String code) {
        return buildContext(aiClient, code, buildNarrative());
    }

    private static Map<String, Object> buildContext(AiClient aiClient, String code, Narrative narrative) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, createWorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, narrative);
        ctx.put(WorkflowKeys.CODE_RESULT, new CodeResult(
                code,
                "DemoScene",
                "demo",
                "Demo concept",
                "Test code evaluation"));
        return ctx;
    }

    private static PocketFlow.Flow<?> evaluationFlow() {
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);
        return new PocketFlow.Flow<>(codeEvaluation);
    }

    private static WorkflowConfig createWorkflowConfig() {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        return config;
    }

    private static Narrative buildNarrative() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();

        Narrative.StoryboardScene scene1 = new Narrative.StoryboardScene();
        scene1.setSceneId("scene_1");
        scene1.setTitle("Intro");
        scene1.setNarration("Introduce the anchor and the first formula.");
        scene1.setDurationSeconds(8);
        scene1.getEnteringObjects().add(object("title", "text", "title"));
        scene1.getEnteringObjects().add(object("eq_main", "formula", "main equation"));
        scene1.getPersistentObjects().add("title");
        scene1.getPersistentObjects().add("eq_main");

        Narrative.StoryboardScene scene2 = new Narrative.StoryboardScene();
        scene2.setSceneId("scene_2");
        scene2.setTitle("Transform");
        scene2.setNarration("Transform the equation into the final result.");
        scene2.setDurationSeconds(8);
        scene2.getEnteringObjects().add(object("eq_result", "formula", "result equation"));
        scene2.getPersistentObjects().add("title");
        scene2.getPersistentObjects().add("eq_result");
        scene2.getExitingObjects().add("eq_main");

        storyboard.getScenes().add(scene1);
        storyboard.getScenes().add(scene2);

        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Demo concept");
        narrative.setTargetDescription("Test code evaluation");
        narrative.setStoryboard(storyboard);
        return narrative;
    }

    private static Narrative buildCompactReviewNarrative() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setHook("Open with a question.");
        storyboard.setSummary("Move from setup to conclusion.");
        storyboard.setContinuityPlan("Keep the title and equation aligned.");
        storyboard.getGlobalVisualRules().add("Keep formulas near the edge.");

        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Intro");
        scene.setGoal("Introduce the relationship.");
        scene.setNarration("Show the title and formula.");
        scene.setDurationSeconds(8);
        scene.setCameraAnchor("center");
        scene.setLayoutGoal("Place the title at the top and the formula below it.");
        scene.setSafeAreaPlan("Keep both objects inside the safe area.");
        scene.getEnteringObjects().add(object("title", "text", "title"));
        scene.getEnteringObjects().add(object("eq_main", "formula", "main equation"));

        Narrative.StoryboardAction action = new Narrative.StoryboardAction();
        action.setOrder(1);
        action.setType("create");
        action.getTargets().add("title");
        action.setDescription("Write the title.");
        scene.getActions().add(action);

        storyboard.getScenes().add(scene);

        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Demo concept");
        narrative.setTargetDescription("Compact storyboard review");
        narrative.setStoryboard(storyboard);
        return narrative;
    }

    private static Narrative buildThreeDNarrative() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();

        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("3D View");
        scene.setGoal("Show the spatial setup.");
        scene.setNarration("Orbit around the 3D axes while keeping the title readable.");
        scene.setDurationSeconds(8);
        scene.setSceneMode("3d");
        scene.setCameraAnchor("center");
        scene.setCameraPlan("Set an oblique view, then orbit slowly.");
        scene.setLayoutGoal("Keep the 3D object centered in projected screen space.");
        scene.setSafeAreaPlan("Keep the projected geometry inside the safe frame.");
        scene.setScreenOverlayPlan("Keep the title fixed in frame.");
        scene.getEnteringObjects().add(object("axes_3d", "geometry", "3D axes"));
        scene.getEnteringObjects().add(object("title", "text", "title"));
        scene.getPersistentObjects().add("axes_3d");
        scene.getPersistentObjects().add("title");

        Narrative.StoryboardAction action = new Narrative.StoryboardAction();
        action.setOrder(1);
        action.setType("camera_rotate");
        action.getTargets().add("axes_3d");
        action.setDescription("Rotate the camera around the axes.");
        scene.getActions().add(action);

        storyboard.getScenes().add(scene);

        Narrative narrative = new Narrative();
        narrative.setTargetConcept("3D demo");
        narrative.setTargetDescription("Check 3D review rules");
        narrative.setStoryboard(storyboard);
        return narrative;
    }

    private static Narrative.StoryboardObject object(String id, String kind, String content) {
        Narrative.StoryboardObject object = new Narrative.StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setContent(content);
        object.setPlacement("center");
        return object;
    }

    private static JsonNode reviewResponse(boolean approved,
                                           int layout,
                                           int continuity,
                                           int pacing,
                                           int clutter,
                                           int offscreen,
                                           String summary,
                                           String blockingIssue,
                                           String directive) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "review_code_quality");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("approved_for_render", approved);
        arguments.put("layout_score", layout);
        arguments.put("continuity_score", continuity);
        arguments.put("pacing_score", pacing);
        arguments.put("clutter_risk", clutter);
        arguments.put("likely_offscreen_risk", offscreen);
        arguments.put("summary", summary);
        arguments.putArray("strengths").add("One clear center anchor.");
        arguments.putArray("blocking_issues").add(blockingIssue);
        arguments.putArray("revision_directives").add(directive);
        function.set("arguments", arguments);
        return response;
    }

    private static String wrapCodeResponse(String code) {
        return "```python\n" + code + "\n```";
    }

    private static String initialCode() {
        return String.join("\n",
                "from manim import *",
                "",
                "class DemoScene(Scene):",
                "    def construct(self):",
                "        title = Text(\"Intro\").to_edge(UP)",
                "        eq_main = MathTex(\"a+b\").to_edge(LEFT)",
                "        eq_result = MathTex(\"c\").to_edge(RIGHT).shift(LEFT * 4)",
                "        self.play(FadeIn(title))",
                "        self.play(FadeIn(eq_main))",
                "        self.play(FadeOut(eq_main))",
                "        self.play(FadeIn(eq_result))");
    }

    private static String revisedCode() {
        return String.join("\n",
                "from manim import *",
                "",
                "class DemoScene(Scene):",
                "    def construct(self):",
                "        title = Text(\"Intro\").to_edge(UP)",
                "        eq_main = MathTex(\"a+b\").next_to(title, DOWN)",
                "        self.play(FadeIn(title), FadeIn(eq_main))",
                "        eq_result = MathTex(\"c\").next_to(title, DOWN)",
                "        self.play(ReplacementTransform(eq_main, eq_result))",
                "        self.wait(0.5)");
    }

    private static final class QueueAiClient implements AiClient {
        private final Deque<JsonNode> toolResponses = new ArrayDeque<>();
        private final Deque<String> chatResponses = new ArrayDeque<>();
        private String lastUserMessage;
        private String lastSystemPrompt;

        @Override
        public String chat(String userMessage, String systemPrompt) {
            lastUserMessage = userMessage;
            lastSystemPrompt = systemPrompt;
            return chatResponses.removeFirst();
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            lastUserMessage = userMessage;
            lastSystemPrompt = systemPrompt;
            return CompletableFuture.completedFuture(toolResponses.removeFirst());
        }

        @Override
        public String providerName() {
            return "test";
        }
    }

    private static final class FailingReviewAiClient implements AiClient {
        @Override
        public String chat(String userMessage, String systemPrompt) {
            return "";
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            return CompletableFuture.failedFuture(new RuntimeException("review unavailable"));
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}

