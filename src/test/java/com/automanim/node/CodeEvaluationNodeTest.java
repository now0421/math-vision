package com.automanim.node;

import com.automanim.config.WorkflowConfig;
import com.automanim.model.CodeResult;
import com.automanim.model.Narrative;
import com.automanim.model.CodeEvaluationResult;
import com.automanim.model.WorkflowKeys;
import com.automanim.service.AiClient;
import com.automanim.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

        new CodeEvaluationNode().run(ctx);

        CodeEvaluationResult result =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);

        assertNotNull(result);
        assertTrue(result.isRevisionTriggered());
        assertTrue(result.isRevisedCodeApplied());
        assertTrue(result.isApprovedForRender());
        assertEquals(1, result.getRevisionAttempts());
        assertEquals(3, result.getToolCalls());
        assertTrue(codeResult.getManimCode().contains("ReplacementTransform"));
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

        new CodeEvaluationNode().run(ctx);

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

    private static Map<String, Object> buildContext(AiClient aiClient, String code) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.AI_CLIENT, aiClient);
        ctx.put(WorkflowKeys.CONFIG, createWorkflowConfig());
        ctx.put(WorkflowKeys.NARRATIVE, buildNarrative());
        ctx.put(WorkflowKeys.CODE_RESULT, new CodeResult(
                code,
                "DemoScene",
                "demo",
                "Demo concept",
                "Test code evaluation"));
        return ctx;
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

        @Override
        public String chat(String userMessage, String systemPrompt) {
            return chatResponses.removeFirst();
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(String userMessage,
                                                                 String systemPrompt,
                                                                 String toolsJson) {
            return CompletableFuture.completedFuture(toolResponses.removeFirst());
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
