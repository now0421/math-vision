package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.CodeEvaluationResult;
import com.mathvision.model.ProblemBundle;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import com.mathvision.prompt.CodeEvaluationPrompts;
import com.mathvision.service.AiClient;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.NodeConversationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.the_pocket.PocketFlow;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
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
        assertEquals("MainScene", codeResult.getSceneName());
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
        aiClient.chatResponses.add(wrapCodeResponse(revisedCodeRoundTwo()));
        aiClient.toolResponses.add(reviewResponse(false, 6, 5, 5, 4, 4,
                "Second revision still does not clear the rule gate.",
                "Scene remains under the threshold.",
                "Try one final rewrite."));
        aiClient.chatResponses.add(wrapCodeResponse(revisedCodeRoundTwo()));
        aiClient.toolResponses.add(reviewResponse(false, 6, 5, 5, 4, 4,
                "Third revision still does not clear the rule gate.",
                "Scene remains under the threshold.",
                "Stop after the third attempt."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode());
        PocketFlow.Flow<?> flow = evaluationFlow();

        flow.run(ctx);

        CodeEvaluationResult result =
                (CodeEvaluationResult) ctx.get(WorkflowKeys.CODE_EVALUATION_RESULT);

        assertNotNull(result);
        assertTrue(result.isRevisionTriggered());
        assertTrue(result.isRevisedCodeApplied());
        assertFalse(result.isApprovedForRender());
        assertEquals(3, result.getRevisionAttempts());
        assertNotNull(result.getGateReason());
        assertFalse(result.getGateReason().isBlank());
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
        ctx.put(WorkflowKeys.PROBLEM_BUNDLE, threeDProblemBundle());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);

        assertFalse(result.isApprovedForRender());
        assertTrue(result.getInitialStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "three_d_scene_required".equals(finding.getRuleId())));
    }

    @Test
    void fallbackReviewAllowsMovingThreeDSceneWithoutOverlayFinding() {
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
        ctx.put(WorkflowKeys.PROBLEM_BUNDLE, threeDProblemBundle());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);

        assertFalse(result.getInitialStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "three_d_overlay_fixed".equals(finding.getRuleId())));
        assertFalse(result.getInitialStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "three_d_camera_set".equals(finding.getRuleId())));
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
        assertEquals(3, aiClient.lastSnapshotSize);
        assertTrue(aiClient.lastUserMessage.contains("\"scenes\""));
        assertTrue(aiClient.lastUserMessage.contains("\"entering_objects\""));
        assertTrue(aiClient.lastUserMessage.contains("\"actions\""));
        assertTrue(aiClient.lastUserMessage.contains("\"safe_area_plan\""));
        assertTrue(aiClient.lastUserMessage.contains("\"goal\""));
        assertTrue(aiClient.lastUserMessage.contains("\"layout_goal\""));
    }

    @Test
    void llmReviewBlocksDerivedObjectHardcoding() {
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(reviewResponse(false, 5, 4, 5, 6, 6,
                "Derived point ignores the storyboard intersection constraint.",
                "Pmin is hardcoded instead of being justified as the intersection of ABprime and l.",
                "Construct or justify Pmin from ABprime and l."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode(), buildDerivedPointNarrative());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertNotNull(aiClient.lastUserMessage);
        assertFalse(result.isApprovedForRender());
        assertEquals(WorkflowActions.FIX_CODE, action);
        assertFalse(result.getFinalStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "constraint_compliance".equals(finding.getRuleId())));
        assertTrue(result.getFinalReview().getBlockingIssues().stream()
                .anyMatch(issue -> issue.contains("intersection of ABprime and l")));
        com.mathvision.model.CodeFixRequest request =
                (com.mathvision.model.CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertTrue(request.getStoryboardJson().contains("\"relation" + "\" : \"intersection_of\""));
        assertTrue(request.getErrorReason().contains("intersection of ABprime and l"));
    }

    @Test
    void fallbackReviewDoesNotStaticallyBlockDerivedObjectNumericCoordinate() {
        Map<String, Object> ctx = buildContext(
                new FailingReviewAiClient(),
                String.join("\n",
                        "from manim import *",
                        "import numpy as np",
                        "",
                        "class MainScene(Scene):",
                        "    def construct(self):",
                        "        self.camera.background_color = \"#000000\"",
                        "        Pmin = Dot(point=np.array([0.6, -1, 0]), color=\"#F9C74F\")",
                        "        self.add(Pmin)"),
                buildDerivedPointNarrative()
        );

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);

        assertFalse(result.getInitialStaticAnalysis().getFindings().stream()
                .anyMatch(finding -> "derived_coordinate_hardcoding".equals(finding.getRuleId())));
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
    }

    @Test
    void codeEvaluationPromptsDoNotApplyMonospaceRuleToMathTex() {
        String reviewRules = CodeEvaluationPrompts.buildReviewRulesPrompt("manim");
        String revisionRules = CodeEvaluationPrompts.buildRevisionRulesPrompt("manim");

        assertTrue(reviewRules.contains("Do not require `MathTex(...)` or `Tex(...)` to use monospace fonts."));
        assertTrue(revisionRules.contains("Do not apply the monospace-font requirement to `MathTex(...)` or `Tex(...)`"));
    }

    @Test
    void codeEvaluationManimRulesIncludeSeverityLevels() {
        String reviewRules = CodeEvaluationPrompts.buildReviewRulesPrompt("manim");
        String geoGebraRules = CodeEvaluationPrompts.buildReviewRulesPrompt("geogebra");

        // Verify severity levels are present in the Manim checklist
        assertTrue(reviewRules.contains("[MANDATORY]"));
        assertTrue(reviewRules.contains("[RECOMMENDED]"));
        assertTrue(reviewRules.contains("ADVISORY"));
        assertTrue(reviewRules.contains("storyboard_contract_compliance` [MANDATORY]"));
        assertTrue(reviewRules.contains("notes_for_codegen"));
        assertTrue(reviewRules.contains("layout_api_usage"));
        assertTrue(reviewRules.contains("three_d_scene_required"));
        assertFalse(reviewRules.contains("three_d_camera_set"));
        assertFalse(reviewRules.contains("three_d_overlay_fixed"));
        assertFalse(reviewRules.contains("`three_d_readability`"));

        // Verify severity levels are present in the GeoGebra checklist
        assertTrue(geoGebraRules.contains("[MANDATORY]"));
        assertTrue(geoGebraRules.contains("[RECOMMENDED]"));
        assertTrue(geoGebraRules.contains("ADVISORY"));
        assertTrue(geoGebraRules.contains("storyboard_contract_compliance` [MANDATORY]"));
        assertTrue(geoGebraRules.contains("notes_for_codegen"));
        assertTrue(geoGebraRules.contains("geogebra_3d_viewport"));
        assertFalse(geoGebraRules.contains("`teaching_coherence`"));
        assertFalse(geoGebraRules.contains("`layout_and_hierarchy`"));
    }

    @Test
    void llmApiWhitelistFailureIsNotDowngradedByCodeEvaluation() {
        String issue = "Static rule warning: undocumented Manim API call `Scene.fake_api`";
        QueueAiClient aiClient = new QueueAiClient();
        aiClient.toolResponses.add(reviewResponse(false, 4, 4, 5, 7, 6,
                "Reviewer incorrectly treated the advisory API warning as blocking.",
                issue,
                "Replace the undocumented API."));

        Map<String, Object> ctx = buildContext(aiClient, initialCode(), buildCompactReviewNarrative());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertFalse(result.isApprovedForRender());
        assertEquals("fail", result.getFinalReview().getRuleChecks().get(0).getStatus());
        assertTrue(result.getFinalReview().getBlockingIssues().contains(issue));
        assertEquals(WorkflowActions.FIX_CODE, action);
        com.mathvision.model.CodeFixRequest request =
                (com.mathvision.model.CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertTrue(request.getErrorReason().contains(issue));
    }

    @Test
    void recommendedSeverityFailureDoesNotBlockRender() {
        QueueAiClient aiClient = new QueueAiClient();
        // Review with a RECOMMENDED-severity failure — should not block render
        aiClient.toolResponses.add(reviewResponseWithSeverity(
                true, "layout_api_usage", "Layout could be improved", "recommended"));

        Map<String, Object> ctx = buildContext(aiClient, initialCode(), buildCompactReviewNarrative());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);

        // RECOMMENDED failure should not block render
        assertTrue(result.isApprovedForRender());
        assertEquals("fail", result.getFinalReview().getRuleChecks().get(0).getStatus());
        assertEquals("recommended", result.getFinalReview().getRuleChecks().get(0).getSeverity());
        assertTrue(result.getFinalReview().getBlockingIssues().isEmpty());
    }

    @Test
    void mandatorySeverityFailureBlocksRender() {
        QueueAiClient aiClient = new QueueAiClient();
        // Review with a MANDATORY-severity failure — should block render
        aiClient.toolResponses.add(reviewResponseWithSeverity(
                false, "manim_code_hygiene", "Code uses undocumented API", "mandatory"));

        Map<String, Object> ctx = buildContext(aiClient, initialCode(), buildCompactReviewNarrative());

        CodeEvaluationNode node = new CodeEvaluationNode();
        CodeEvaluationNode.CodeEvaluationInput input = node.prep(ctx);
        CodeEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertFalse(result.isApprovedForRender());
        assertEquals("mandatory", result.getFinalReview().getRuleChecks().get(0).getSeverity());
        assertEquals(WorkflowActions.FIX_CODE, action);
    }

    @Test
    void evaluationFixReasonExtractsCleanSummariesWithoutDuplication() {
        String issue = "Static rule violation: undocumented GeoGebra command "
                + "(line 1: SetFontSize() - SetFontSize(label, 18))";

        CodeResult codeResult = new CodeResult(
                "SetFontSize(label, 18)",
                "GeoGebraFigure",
                "geogebra",
                "Demo concept",
                "Test code evaluation");

        CodeEvaluationResult.StaticAnalysis analysis = new CodeEvaluationResult.StaticAnalysis();
        analysis.setFindings(List.of(new CodeEvaluationResult.StaticFinding(
                "geogebra_syntax",
                "fail",
                issue,
                issue)));

        CodeEvaluationResult.ReviewSnapshot review = new CodeEvaluationResult.ReviewSnapshot();
        review.setRuleChecks(List.of(new CodeEvaluationResult.RuleCheck(
                "geogebra_syntax", issue, "fail", issue)));
        review.setBlockingIssues(List.of(issue));

        CodeEvaluationResult result = new CodeEvaluationResult();
        result.setApprovedForRender(false);
        result.setSceneName("GeoGebraFigure");
        result.setGateReason(issue);
        result.setFinalStaticAnalysis(analysis);
        result.setFinalReview(review);

        CodeEvaluationNode.EvaluationFixState fixState = new CodeEvaluationNode.EvaluationFixState();
        fixState.setRequestFix(true);
        CodeEvaluationNode.CodeEvaluationInput input = new CodeEvaluationNode.CodeEvaluationInput(
                codeResult,
                buildNarrative(),
                createWorkflowConfig(),
                null,
                null,
                fixState);

        Map<String, Object> ctx = new LinkedHashMap<>();
        String action = new CodeEvaluationNode().post(ctx, input, result);

        assertEquals(WorkflowActions.FIX_CODE, action);
        com.mathvision.model.CodeFixRequest request =
                (com.mathvision.model.CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        String errorReason = request.getErrorReason();
        // No [evidence:] tags — LLM sees the full JSON already
        assertFalse(errorReason.contains("[evidence:"));
        // No "Gate summary:" prefix — same data is extracted from structured objects
        assertFalse(errorReason.contains("Gate summary:"));
        // The same issue text appears only once across findings + ruleChecks + blockingIssues
        assertEquals(errorReason.indexOf(issue), errorReason.lastIndexOf(issue));
        // The error reason contains the issue summary
        assertTrue(errorReason.contains(issue));
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
                "MainScene",
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

    private static ProblemBundle threeDProblemBundle() {
        ProblemBundle bundle = new ProblemBundle();
        bundle.setSceneMode("3d");
        return bundle;
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
        scene1.getPersistentObjects().add(idOnly("title"));
        scene1.getPersistentObjects().add(idOnly("eq_main"));

        Narrative.StoryboardScene scene2 = new Narrative.StoryboardScene();
        scene2.setSceneId("scene_2");
        scene2.setTitle("Transform");
        scene2.setNarration("Transform the equation into the final result.");
        scene2.setDurationSeconds(8);
        scene2.getEnteringObjects().add(object("eq_result", "formula", "result equation"));
        scene2.getPersistentObjects().add(idOnly("title"));
        scene2.getPersistentObjects().add(idOnly("eq_result"));
        scene2.getExitingObjects().add(idOnly("eq_main"));

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
        scene.setCameraAnchor("center");
        scene.setCameraPlan("Set an oblique view, then orbit slowly.");
        scene.setLayoutGoal("Keep the 3D object centered in projected screen space.");
        scene.setSafeAreaPlan("Keep the projected geometry inside the safe frame.");
        scene.setScreenOverlayPlan("Keep the title fixed in frame.");
        scene.getEnteringObjects().add(object("axes_3d", "geometry", "3D axes"));
        scene.getEnteringObjects().add(object("title", "text", "title"));
        scene.getPersistentObjects().add(idOnly("axes_3d"));
        scene.getPersistentObjects().add(idOnly("title"));

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

    private static Narrative buildDerivedPointNarrative() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();

        Narrative.StoryboardObject pmin = object("Pmin", "point", "optimal stop");
        pmin.setConstraints(List.of(constraint(
                "Pmin_intersection",
                "construction",
                "intersection_of",
                Map.of("point", "Pmin", "object_a", "ABprime", "object_b", "l"),
                Map.of(),
                "hard")));
        storyboard.getObjectRegistry().add(object("ABprime", "segment", "Shortcut segment"));
        storyboard.getObjectRegistry().add(object("l", "line", "Boundary line"));
        storyboard.getObjectRegistry().add(pmin);

        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Shortcut");
        scene.setGoal("Mark the intersection.");
        scene.setLayoutGoal("Place Pmin on the crossing of ABprime and l.");
        Narrative.StoryboardObject pminPatch = idOnly("Pmin");
        pminPatch.setPlacement(placement(0.6, -1.0));
        scene.getEnteringObjects().add(pminPatch);
        storyboard.getScenes().add(scene);

        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Derived point demo");
        narrative.setTargetDescription("Review derived coordinate handling");
        narrative.setStoryboard(storyboard);
        return narrative;
    }

    private static Narrative.StoryboardObject object(String id, String kind, String content) {
        Narrative.StoryboardObject object = new Narrative.StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setContent(content);
        return object;
    }

    private static Narrative.StoryboardPlacement placement(double x, double y) {
        Narrative.StoryboardPlacement placement = new Narrative.StoryboardPlacement();
        placement.setPositioning(Narrative.StoryboardPlacement.POSITIONING_ABSOLUTE);
        Narrative.StoryboardPlacementAxis xAxis = new Narrative.StoryboardPlacementAxis();
        xAxis.setValue(x);
        Narrative.StoryboardPlacementAxis yAxis = new Narrative.StoryboardPlacementAxis();
        yAxis.setValue(y);
        placement.setX(xAxis);
        placement.setY(yAxis);
        return placement;
    }

    private static Narrative.StoryboardObject idOnly(String id) {
        Narrative.StoryboardObject object = new Narrative.StoryboardObject();
        object.setId(id);
        return object;
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
        function.put("name", "write_code_review");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("approved_for_render", approved);
        ArrayNode ruleChecks = arguments.putArray("rule_checks");
        ObjectNode ruleCheck = ruleChecks.addObject();
        ruleCheck.put("rule_id", approved ? "storyboard_contract_compliance" : "manim_code_hygiene");
        ruleCheck.put("requirement", approved
                ? "Storyboard execution and presentation rules are satisfied."
                : blockingIssue);
        ruleCheck.put("status", approved ? "pass" : "fail");
        ruleCheck.put("evidence", approved
                ? "The test response marks the generated code as render-ready."
                : "The test response provides a blocking issue.");
        ruleCheck.put("severity", approved ? "advisory" : "mandatory");
        arguments.put("summary", summary);
        arguments.putArray("strengths").add("One clear center anchor.");
        ArrayNode blockingIssues = arguments.putArray("blocking_issues");
        if (!approved) {
            blockingIssues.add(blockingIssue);
        }
        arguments.putArray("revision_directives").add(directive);
        function.set("arguments", arguments);
        return response;
    }

    private static String wrapCodeResponse(String code) {
        return "```python\n" + code + "\n```";
    }

    private static JsonNode reviewResponseWithSeverity(boolean approved,
                                                       String ruleId,
                                                       String requirement,
                                                       String severity) {
        ObjectNode response = JsonUtils.mapper().createObjectNode();
        ArrayNode choices = response.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode function = toolCalls.addObject().putObject("function");
        function.put("name", "write_code_review");

        ObjectNode arguments = JsonUtils.mapper().createObjectNode();
        arguments.put("approved_for_render", approved);
        ArrayNode ruleChecks = arguments.putArray("rule_checks");
        ObjectNode ruleCheck = ruleChecks.addObject();
        ruleCheck.put("rule_id", ruleId);
        ruleCheck.put("requirement", requirement);
        ruleCheck.put("status", "fail");
        ruleCheck.put("evidence", "Test evidence for severity check.");
        ruleCheck.put("severity", severity);
        arguments.put("summary", "Review with severity check.");
        arguments.putArray("strengths").add("One clear center anchor.");
        if (!approved) {
            arguments.putArray("blocking_issues").add(requirement);
        } else {
            arguments.putArray("blocking_issues");
        }
        arguments.putArray("revision_directives").add("Fix the issue.");
        function.set("arguments", arguments);
        return response;
    }

    private static String initialCode() {
        return String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
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
                "class MainScene(Scene):",
                "    def construct(self):",
                "        title = Text(\"Intro\").to_edge(UP)",
                "        eq_main = MathTex(\"a+b\").next_to(title, DOWN)",
                "        self.play(FadeIn(title), FadeIn(eq_main))",
                "        eq_result = MathTex(\"c\").next_to(title, DOWN)",
                "        self.play(ReplacementTransform(eq_main, eq_result))",
                "        self.wait(0.5)");
    }

    private static String revisedCodeRoundTwo() {
        return String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        title = Text(\"Intro\").to_edge(UP)",
                "        eq_main = MathTex(\"a+b\").next_to(title, DOWN)",
                "        self.play(FadeIn(title), FadeIn(eq_main))",
                "        eq_result = MathTex(\"c\").next_to(eq_main, DOWN)",
                "        self.play(Transform(eq_main, eq_result))",
                "        self.wait(0.5)");
    }

    private static final class QueueAiClient implements AiClient {
        private final Deque<JsonNode> toolResponses = new ArrayDeque<>();
        private final Deque<String> chatResponses = new ArrayDeque<>();
        private String lastUserMessage;
        private String lastSystemPrompt;
        private int lastSnapshotSize;

        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            lastSnapshotSize = snapshot.size();
            lastUserMessage = snapshot.get(snapshot.size() - 1).getContent();
            lastSystemPrompt = NodeConversationContext.getSystemContent(snapshot);
            return CompletableFuture.completedFuture(chatResponses.removeFirst());
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(List<NodeConversationContext.Message> snapshot,
                                                                 String toolsJson) {
            lastSnapshotSize = snapshot.size();
            lastUserMessage = snapshot.get(snapshot.size() - 1).getContent();
            lastSystemPrompt = NodeConversationContext.getSystemContent(snapshot);
            if (!com.mathvision.prompt.ToolSchemas.CODE_REVIEW.equals(toolsJson)) {
                return CompletableFuture.failedFuture(new RuntimeException("tools not used"));
            }
            return CompletableFuture.completedFuture(toolResponses.removeFirst());
        }

        @Override
        public String providerName() {
            return "test";
        }
    }

    private static final class FailingReviewAiClient implements AiClient {
        @Override
        public CompletableFuture<String> chatAsync(List<NodeConversationContext.Message> snapshot) {
            return CompletableFuture.completedFuture("");
        }

        @Override
        public CompletableFuture<JsonNode> chatWithToolsRawAsync(List<NodeConversationContext.Message> snapshot,
                                                                 String toolsJson) {
            return CompletableFuture.failedFuture(new RuntimeException("review unavailable"));
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}

