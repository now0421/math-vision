package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.CodeFixRequest;
import com.mathvision.model.CodeFixSource;
import com.mathvision.model.CodeResult;
import com.mathvision.model.Narrative;
import com.mathvision.model.RenderResult;
import com.mathvision.model.SceneEvaluationResult;
import com.mathvision.model.WorkflowActions;
import com.mathvision.model.WorkflowKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneEvaluationNodeTest {

    @TempDir
    Path tempDir;

    @Test
    void approvesWhenAllSamplesAreWithinFrameAndNonOverlapping() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, cleanGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertTrue(result.isApproved());
        assertEquals(0, result.getTotalIssueCount());
        assertNull(action);
        assertTrue(Files.exists(tempDir.resolve("6_scene_evaluation.json")));
    }

    @Test
    void requestsCodeFixWhenGeometryContainsOverlapOrOffscreenIssues() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, problematicGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertFalse(result.isApproved());
        assertTrue(result.isRevisionTriggered());
        assertTrue(result.getTotalIssueCount() >= 2);
        assertEquals(WorkflowActions.FIX_CODE, action);

        CodeFixRequest request = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertEquals(CodeFixSource.SCENE_LAYOUT_EVALUATION, request.getSource());
        assertEquals(WorkflowActions.RETRY_RENDER, request.getReturnAction());
        assertNotNull(request.getSceneEvaluationJson());
        assertTrue(request.getSceneEvaluationJson().contains("\"issue_sample_count\""));
    }

    @Test
    void firstSceneEvaluationFixRequestDoesNotIncludeCurrentIssueInFixHistory() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, problematicGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput firstInput = node.prep(ctx);
        SceneEvaluationResult firstResult = node.exec(firstInput);
        String firstAction = node.post(ctx, firstInput, firstResult);

        assertEquals(WorkflowActions.FIX_CODE, firstAction);
        CodeFixRequest firstRequest = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(firstRequest);
        assertTrue(firstRequest.getFixHistory().isEmpty());

        SceneEvaluationNode.SceneEvaluationInput secondInput = node.prep(ctx);
        SceneEvaluationResult secondResult = node.exec(secondInput);
        String secondAction = node.post(ctx, secondInput, secondResult);

        assertEquals(WorkflowActions.FIX_CODE, secondAction);
        CodeFixRequest secondRequest = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(secondRequest);
        assertEquals(1, secondRequest.getFixHistory().size());
        assertTrue(secondRequest.getFixHistory().get(0).contains("Scene evaluation found"));
    }

    @Test
    void sceneEvaluationFixRequestUsesDetailedStoryboardJson() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, problematicGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath, buildSceneFixNarrative());
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertEquals(WorkflowActions.FIX_CODE, action);
        CodeFixRequest request = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertTrue(request.getStoryboardJson().contains("\"goal\""));
        assertTrue(request.getStoryboardJson().contains("\"layout_goal\""));
        assertTrue(request.getStoryboardJson().contains("\"constraint_note\""));
        assertTrue(request.getStoryboardJson().contains("\"summary\""));
    }

    @Test
    void ignoresNonTextOnlyOverlapPairs() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, nonTextOverlapGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertTrue(result.isApproved());
        assertEquals(0, result.getOverlapIssueCount());
        assertNull(action);
    }

    @Test
    void ignoresArcBBoxOverlapWhenSampledPathMissesText() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, arcBBoxFalsePositiveGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertTrue(result.isApproved());
        assertEquals(0, result.getOverlapIssueCount());
        assertNull(action);
    }

    @Test
    void ignoresInvisibleElementsDuringOverlapEvaluation() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, invisibleOverlapGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertTrue(result.isApproved());
        assertEquals(0, result.getOverlapIssueCount());
        assertNull(action);
    }

    @Test
    void prefersProjectedScreenBoundsWhenPresent() throws IOException {
        Path geometryPath = tempDir.resolve("5_mobject_geometry.json");
        Files.writeString(geometryPath, projectedGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertTrue(result.isApproved());
        assertEquals(0, result.getTotalIssueCount());
        assertNull(action);
    }

    private Map<String, Object> buildContext(Path geometryPath) {
        return buildContext(geometryPath, null);
    }

    private Map<String, Object> buildContext(Path geometryPath, Narrative narrative) {
        WorkflowConfig config = new WorkflowConfig();
        config.setRenderEnabled(true);
        config.setSceneEvaluationMaxRetries(2);

        CodeResult codeResult = new CodeResult(
                String.join("\n",
                        "from manim import *",
                        "",
                        "class MainScene(Scene):",
                        "    def construct(self):",
                        "        self.wait(1)"),
                "MainScene",
                "demo",
                "Demo concept",
                "Demo description");

        RenderResult renderResult = new RenderResult();
        renderResult.setSuccess(true);
        renderResult.setSceneName("MainScene");
        renderResult.setGeometryPath(geometryPath.toString());
        renderResult.setAttempts(1);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.CODE_RESULT, codeResult);
        ctx.put(WorkflowKeys.RENDER_RESULT, renderResult);
        if (narrative != null) {
            ctx.put(WorkflowKeys.NARRATIVE, narrative);
        }
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);
        return ctx;
    }

    private Narrative buildSceneFixNarrative() {
        Narrative.StoryboardObject pointBPrime = new Narrative.StoryboardObject();
        pointBPrime.setId("point_Bprime");
        pointBPrime.setKind("point");
        pointBPrime.setContent("Reflection of B across l");
        pointBPrime.setBehavior("derived");
        pointBPrime.setConstraintNote("Reflection of B across l");

        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_2");
        scene.setTitle("Reflect B");
        scene.setGoal("Keep the reflection construction exact while cleaning the layout.");
        scene.setNarration("Reflect B across l and preserve the mirror symmetry.");
        scene.setLayoutGoal("Keep explanatory text off the core geometry while B' stays symmetric to B.");
        scene.getGeometryConstraints().add("B' is the exact reflection of B across line l");
        scene.getEnteringObjects().add(pointBPrime);
        Narrative.StoryboardObject persistB = new Narrative.StoryboardObject();
        persistB.setId("point_B");
        Narrative.StoryboardObject persistBprime = new Narrative.StoryboardObject();
        persistBprime.setId("point_Bprime");
        scene.getPersistentObjects().add(persistB);
        scene.getPersistentObjects().add(persistBprime);
        scene.getActions().add(new Narrative.StoryboardAction());

        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setHook("Demo hook");
        storyboard.setSummary("Demo summary");
        storyboard.setContinuityPlan("Preserve the same diagram while repairing layout.");
        storyboard.getScenes().add(scene);

        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Demo concept");
        narrative.setTargetDescription("Demo description");
        narrative.setStoryboard(storyboard);
        return narrative;
    }

    private String cleanGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"MainScene\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.111111, -4.0, 0.0],",
                "    \"max\": [7.111111, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"sample-0001\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"title\",",
                "          \"semantic_name\": \"title\",",
                "          \"class_name\": \"Text\",",
                "          \"semantic_class\": \"text\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [-1.5, 2.0, 0.0],",
                "            \"max\": [1.5, 2.8, 0.0]",
                "          }",
                "        },",
                "        {",
                "          \"stable_id\": \"diagram\",",
                "          \"semantic_name\": \"diagram\",",
                "          \"class_name\": \"Circle\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [-1.0, -1.0, 0.0],",
                "            \"max\": [1.0, 1.0, 0.0]",
                "          }",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String problematicGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"MainScene\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.111111, -4.0, 0.0],",
                "    \"max\": [7.111111, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"sample-0001\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"scene_method\": \"construct\",",
                "      \"source_code\": \"self.play(FadeIn(title), FadeIn(dot))\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"title\",",
                "          \"semantic_name\": \"title\",",
                "          \"class_name\": \"Text\",",
                "          \"semantic_class\": \"text\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [-1.0, -0.5, 0.0],",
                "            \"max\": [1.0, 0.5, 0.0]",
                "          }",
                "        },",
                "        {",
                "          \"stable_id\": \"dot\",",
                "          \"semantic_name\": \"dot\",",
                "          \"class_name\": \"Dot\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [0.2, -0.2, 0.0],",
                "            \"max\": [1.2, 0.8, 0.0]",
                "          }",
                "        },",
                "        {",
                "          \"stable_id\": \"formula\",",
                "          \"semantic_name\": \"formula\",",
                "          \"class_name\": \"MathTex\",",
                "          \"semantic_class\": \"formula\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [6.8, 3.2, 0.0],",
                "            \"max\": [7.5, 4.4, 0.0]",
                "          }",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String projectedGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"MainScene\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.111111, -4.0, 0.0],",
                "    \"max\": [7.111111, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"sample-0001\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"surface\",",
                "          \"semantic_name\": \"surface\",",
                "          \"class_name\": \"Surface\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [9.0, 9.0, -2.0],",
                "            \"max\": [12.0, 12.0, 2.0]",
                "          },",
                "          \"screen_bounds\": {",
                "            \"min\": [-1.5, -1.0, -0.2],",
                "            \"max\": [1.5, 1.0, 0.2]",
                "          }",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String nonTextOverlapGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"MainScene\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.111111, -4.0, 0.0],",
                "    \"max\": [7.111111, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"sample-0001\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"brace\",",
                "          \"semantic_name\": \"brace\",",
                "          \"class_name\": \"Brace\",",
                "          \"semantic_class\": \"other\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [3.8, -0.4, 0.0],",
                "            \"max\": [4.4, 0.9, 0.0]",
                "          }",
                "        },",
                "        {",
                "          \"stable_id\": \"bar\",",
                "          \"semantic_name\": \"bar\",",
                "          \"class_name\": \"Rectangle\",",
                "          \"semantic_class\": \"shape\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [4.0, 0.1, 0.0],",
                "            \"max\": [5.2, 1.1, 0.0]",
                "          }",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String invisibleOverlapGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"MainScene\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.111111, -4.0, 0.0],",
                "    \"max\": [7.111111, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"sample-0001\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"hidden_formula\",",
                "          \"semantic_name\": \"hidden_formula\",",
                "          \"class_name\": \"MathTex\",",
                "          \"semantic_class\": \"formula\",",
                "          \"visible\": false,",
                "          \"bounds\": {",
                "            \"min\": [-1.0, -0.5, 0.0],",
                "            \"max\": [1.0, 0.5, 0.0]",
                "          }",
                "        },",
                "        {",
                "          \"stable_id\": \"shown_box\",",
                "          \"semantic_name\": \"shown_box\",",
                "          \"class_name\": \"Rectangle\",",
                "          \"semantic_class\": \"shape\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [-0.8, -0.4, 0.0],",
                "            \"max\": [0.8, 0.4, 0.0]",
                "          }",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String arcBBoxFalsePositiveGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"MainScene\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.111111, -4.0, 0.0],",
                "    \"max\": [7.111111, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"sample-0001\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"label\",",
                "          \"semantic_name\": \"label\",",
                "          \"class_name\": \"MathTex\",",
                "          \"semantic_class\": \"formula\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [-0.5, -0.2, 0.0],",
                "            \"max\": [0.5, 0.2, 0.0]",
                "          }",
                "        },",
                "        {",
                "          \"stable_id\": \"arc\",",
                "          \"semantic_name\": \"arc_alpha\",",
                "          \"class_name\": \"Angle\",",
                "          \"semantic_class\": \"other\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [-1.0, -1.0, 0.0],",
                "            \"max\": [1.0, 1.0, 0.0]",
                "          },",
                "          \"shape_hints\": {",
                "            \"path_points\": [",
                "              [1.0, 0.0, 0.0],",
                "              [0.7, 0.7, 0.0],",
                "              [0.0, 1.0, 0.0],",
                "              [-0.7, 0.7, 0.0],",
                "              [-1.0, 0.0, 0.0]",
                "            ]",
                "          }",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }
}
