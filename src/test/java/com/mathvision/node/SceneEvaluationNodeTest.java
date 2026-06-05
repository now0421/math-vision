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
import com.mathvision.service.FileOutputService;
import com.mathvision.service.GeoGebraRenderService;
import com.mathvision.service.ManimRendererService;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
        assertTrue(Files.exists(tempDir.resolve(FileOutputService.SCENE_EVALUATION_FILE)));
    }

    @Test
    void requestsCodeFixWhenGeometryContainsOverlapOrOffscreenIssues() throws IOException {
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
        assertTrue(request.getSceneEvaluationJson().contains("expand_axes_coordinate_range_first"));
    }

    @Test
    void firstSceneEvaluationFixRequestDoesNotIncludeCurrentIssueInFixHistory() throws IOException {
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
    void readsGeoGebraEvaluationShapeWithoutRectangleOnlyOverlap() throws IOException {
        Path geometryPath = tempDir.resolve(GeoGebraRenderService.GEOMETRY_FILE);
        Files.writeString(geometryPath, geoGebraStructuredGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        ((WorkflowConfig) ctx.get(WorkflowKeys.CONFIG)).setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        renderResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        renderResult.setArtifactType("geogebra_preview_html");
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
    void clipsGeoGebraInfiniteLineBucketsToFrameBounds() throws IOException {
        Path geometryPath = tempDir.resolve(GeoGebraRenderService.GEOMETRY_FILE);
        Files.writeString(geometryPath, geoGebraInfiniteLineGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath);
        ((WorkflowConfig) ctx.get(WorkflowKeys.CONFIG)).setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        renderResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        renderResult.setArtifactType("geogebra_preview_html");
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertEquals(1, result.getOffscreenIssueCount());
        assertEquals(0, result.getOverlapIssueCount());
        assertEquals(WorkflowActions.FIX_CODE, action);
        CodeFixRequest request = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        assertTrue(request.getSceneEvaluationJson().contains("expand_set_coord_system_range_first"));
    }

    @Test
    void geoGebraSceneEvaluationUsesCodeSetCoordSystemInsteadOfStoryboardBounds() throws IOException {
        Path geometryPath = tempDir.resolve(GeoGebraRenderService.GEOMETRY_FILE);
        Files.writeString(geometryPath, geoGebraWideCodeViewportGeometryJson());

        Narrative narrative = new Narrative("Demo", "Demo", new Narrative.Storyboard());
        Narrative.StoryboardCoordinateBounds storyboardBounds = new Narrative.StoryboardCoordinateBounds();
        storyboardBounds.setX(new Narrative.StoryboardCoordinateBoundsAxis(-7.0, 7.0));
        storyboardBounds.setY(new Narrative.StoryboardCoordinateBoundsAxis(-4.0, 4.0));
        narrative.getStoryboard().setCoordinateBounds(storyboardBounds);
        Map<String, Object> ctx = buildContext(geometryPath, narrative);
        ((WorkflowConfig) ctx.get(WorkflowKeys.CONFIG)).setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        CodeResult codeResult = (CodeResult) ctx.get(WorkflowKeys.CODE_RESULT);
        codeResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        codeResult.setGeneratedCode("SetCoordSystem(-20, 20, -10, 10)\nA = (8, 0)");
        RenderResult renderResult = (RenderResult) ctx.get(WorkflowKeys.RENDER_RESULT);
        renderResult.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        renderResult.setArtifactType("geogebra_preview_html");
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertTrue(result.isEvaluated());
        assertTrue(result.isApproved(),
                () -> "issues=" + result.getTotalIssueCount() + ", gate=" + result.getGateReason());
        assertEquals(0, result.getOffscreenIssueCount());
        assertNull(action);
    }

    @Test
    void sceneEvaluationFixRequestUsesDetailedStoryboardJson() throws IOException {
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
        assertTrue(request.getStoryboardJson().contains("\"constraints\""));
        assertFalse(request.getStoryboardJson().contains("\"constraint_note\""));

        JsonNode storyboardJson = JsonUtils.mapper().readTree(request.getStoryboardJson());
        JsonNode registryBprime = findObject(storyboardJson.get("object_registry"), "point_Bprime");
        JsonNode sceneBprime = findObject(storyboardJson.get("scenes").get(0).get("entering_objects"), "point_Bprime");
        assertFalse(registryBprime.has("placement"));
        assertFalse(sceneBprime.has("placement"));
        assertFalse(sceneBprime.has("constraints"));
        assertFalse(request.getStoryboardJson().contains("\"placement\""));
    }

    @Test
    void sceneEvaluationFixReportIncludesStoryboardDependencyChain() throws IOException {
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
        Files.writeString(geometryPath, derivedOffscreenGeometryJson());

        Map<String, Object> ctx = buildContext(geometryPath, buildSceneFixNarrative());
        SceneEvaluationNode node = new SceneEvaluationNode();

        SceneEvaluationNode.SceneEvaluationInput input = node.prep(ctx);
        SceneEvaluationResult result = node.exec(input);
        String action = node.post(ctx, input, result);

        assertEquals(WorkflowActions.FIX_CODE, action);
        CodeFixRequest request = (CodeFixRequest) ctx.get(WorkflowKeys.CODE_FIX_REQUEST);
        assertNotNull(request);
        String sceneEvaluationJson = request.getSceneEvaluationJson();
        assertTrue(sceneEvaluationJson.contains("\"storyboard_dependency_context\""));
        assertTrue(sceneEvaluationJson.contains("\"full_dependency_chain\""));
        assertTrue(sceneEvaluationJson.contains("\"point_Bprime\""));
        assertTrue(sceneEvaluationJson.contains("\"point_B\""));
        assertTrue(sceneEvaluationJson.contains("\"line_l\""));
        assertFalse(sceneEvaluationJson.contains("\"derived_placement_omitted\""));
    }

    @Test
    void ignoresNonTextOnlyOverlapPairs() throws IOException {
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
    void ignoresLineSegmentIntersectionsForBothBackends() throws IOException {
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
        Files.writeString(geometryPath, intersectingSegmentsGeometryJson());

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
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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
        Path geometryPath = tempDir.resolve(ManimRendererService.GEOMETRY_EXPORT_OUTPUT_FILE);
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

    private JsonNode findObject(JsonNode objects, String id) {
        assertNotNull(objects);
        for (JsonNode object : objects) {
            if (id.equals(object.path("id").asText())) {
                return object;
            }
        }
        throw new AssertionError("missing object " + id);
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
        Narrative.StoryboardObject pointB = new Narrative.StoryboardObject();
        pointB.setId("point_B");
        pointB.setKind("point");

        Narrative.StoryboardObject lineL = new Narrative.StoryboardObject();
        lineL.setId("line_l");
        lineL.setKind("line");

        Narrative.StoryboardObject pointBPrime = new Narrative.StoryboardObject();
        pointBPrime.setId("point_Bprime");
        pointBPrime.setKind("point");
        pointBPrime.setContent("Reflection of B across l");
        pointBPrime.setConstraints(List.of(constraint(
                "point_Bprime_reflection",
                "construction",
                "reflection_across",
                Map.of("image", "point_Bprime", "source", "point_B", "mirror", "line_l"),
                Map.of(),
                "hard",
                "B' is the exact reflection of B across line l")));
        pointBPrime.setPlacement(placement(8.0, 3.8));

        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_2");
        scene.setTitle("Reflect B");
        scene.setGoal("Keep the reflection construction exact while cleaning the layout.");
        scene.setNarration("Reflect B across l and preserve the mirror symmetry.");
        scene.setLayoutGoal("Keep explanatory text off the core geometry while B' stays symmetric to B.");
        scene.getConstraints().add(constraint(
                "scene_reflection_Bprime",
                "construction",
                "reflection_across",
                Map.of("image", "point_Bprime", "source", "point_B", "mirror", "line_l"),
                Map.of(),
                "hard",
                "B' is the exact reflection of B across line l"));
        scene.getEnteringObjects().add(pointBPrime);
        Narrative.StoryboardObject persistB = new Narrative.StoryboardObject();
        persistB.setId("point_B");
        Narrative.StoryboardObject persistBprime = new Narrative.StoryboardObject();
        persistBprime.setId("point_Bprime");
        scene.getPersistentObjects().add(persistB);
        scene.getPersistentObjects().add(persistBprime);
        scene.getActions().add(new Narrative.StoryboardAction());

        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        storyboard.setContinuityPlan("Preserve the same diagram while repairing layout.");
        storyboard.setObjectRegistry(List.of(pointB, lineL, pointBPrime));
        storyboard.getScenes().add(scene);

        Narrative narrative = new Narrative();
        narrative.setTargetConcept("Demo concept");
        narrative.setTargetDescription("Demo description");
        narrative.setStoryboard(storyboard);
        return narrative;
    }

    private Narrative.StoryboardPlacement placement(double x, double y) {
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

    private Narrative.StoryboardConstraint constraint(String id,
                                                      String domain,
                                                      String relation,
                                                      Map<String, Object> refs,
                                                      Map<String, Object> parameters,
                                                      String strength,
                                                      String reason) {
        Narrative.StoryboardConstraint constraint = new Narrative.StoryboardConstraint();
        constraint.setId(id);
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength(strength);
        constraint.setReason(reason);
        return constraint;
    }

    private String geoGebraStructuredGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"GeoGebraFigure\",",
                "  \"report_type\": \"geogebra_element_report\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.0, -4.0, 0.0],",
                "    \"max\": [7.0, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"geogebra-initial\",",
                "      \"sample_role\": \"geogebra_construction\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"ggb-AB\",",
                "          \"semantic_name\": \"AB\",",
                "          \"class_name\": \"segment\",",
                "          \"semantic_class\": \"line\",",
                "          \"visible\": true,",
                "          \"geometry_type\": \"segment\",",
                "          \"geometry_points\": [[-2.0, 0.0, 0.0], [2.0, 0.0, 0.0]],",
                "          \"evaluation_shape\": {\"type\": \"segment\", \"points\": [[-2.0, 0.0, 0.0], [2.0, 0.0, 0.0]]},",
                "          \"bounds\": {\"min\": [-2.0, -0.05, 0.0], \"max\": [2.0, 0.05, 0.0]}",
                "        },",
                "        {",
                "          \"stable_id\": \"ggb-c\",",
                "          \"semantic_name\": \"c\",",
                "          \"class_name\": \"circle\",",
                "          \"semantic_class\": \"shape\",",
                "          \"visible\": true,",
                "          \"geometry_type\": \"circle\",",
                "          \"evaluation_shape\": {\"type\": \"circle\", \"center\": [0.0, 0.0, 0.0], \"radius\": 2.0},",
                "          \"bounds\": {\"min\": [-2.0, -2.0, 0.0], \"max\": [2.0, 2.0, 0.0]}",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String geoGebraInfiniteLineGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"GeoGebraFigure\",",
                "  \"report_type\": \"geogebra_element_report\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.0, -4.0, 0.0],",
                "    \"max\": [7.0, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"scene_1\",",
                "      \"sample_role\": \"scene_final\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"ggb-g\",",
                "          \"semantic_name\": \"g\",",
                "          \"class_name\": \"line\",",
                "          \"semantic_class\": \"line\",",
                "          \"visible\": true,",
                "          \"geometry_type\": \"line\",",
                "          \"geometry_points\": [[-6003.2, 4601.55, 0.0], [5996.8, -4598.45, 0.0]],",
                "          \"evaluation_shape\": {\"type\": \"segment\", \"points\": [[-6003.2, 4601.55, 0.0], [5996.8, -4598.45, 0.0]], \"source_type\": \"line\"},",
                "          \"bounds\": {\"min\": [-6003.25, -4598.5, 0.0], \"max\": [5996.85, 4601.6, 0.0]}",
                "        },",
                "        {",
                "          \"stable_id\": \"label\",",
                "          \"semantic_name\": \"label\",",
                "          \"class_name\": \"Text\",",
                "          \"semantic_class\": \"text\",",
                "          \"visible\": true,",
                "          \"bounds\": {\"min\": [5.0, 3.0, 0.0], \"max\": [6.0, 3.5, 0.0]}",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
    }

    private String geoGebraWideCodeViewportGeometryJson() {
        return String.join("\n",
                "{",
                "  \"scene_name\": \"GeoGebraFigure\",",
                "  \"report_type\": \"geogebra_element_report\",",
                "  \"frame_bounds\": {",
                "    \"min\": [-7.0, -4.0, 0.0],",
                "    \"max\": [7.0, 4.0, 0.0]",
                "  },",
                "  \"samples\": [",
                "    {",
                "      \"sample_id\": \"geogebra-initial\",",
                "      \"sample_role\": \"geogebra_construction\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"ggb-A\",",
                "          \"semantic_name\": \"A\",",
                "          \"class_name\": \"point\",",
                "          \"semantic_class\": \"point\",",
                "          \"visible\": true,",
                "          \"geometry_type\": \"point\",",
                "          \"bounds\": {\"min\": [8.0, 0.0, 0.0], \"max\": [8.0, 0.0, 0.0]}",
                "        }",
                "      ]",
                "    }",
                "  ]",
                "}");
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

    private String derivedOffscreenGeometryJson() {
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
                "      \"source_code\": \"self.play(FadeIn(point_Bprime))\",",
                "      \"elements\": [",
                "        {",
                "          \"stable_id\": \"mob-0003\",",
                "          \"semantic_name\": \"point_Bprime\",",
                "          \"class_name\": \"Dot\",",
                "          \"semantic_class\": \"point\",",
                "          \"visible\": true,",
                "          \"bounds\": {",
                "            \"min\": [7.0, 3.6, 0.0],",
                "            \"max\": [7.5, 4.3, 0.0]",
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

    private String intersectingSegmentsGeometryJson() {
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
                "          \"stable_id\": \"line_a\",",
                "          \"semantic_name\": \"line_a\",",
                "          \"class_name\": \"Line\",",
                "          \"semantic_class\": \"line\",",
                "          \"visible\": true,",
                "          \"bounds\": {\"min\": [-1.0, -1.0, 0.0], \"max\": [1.0, 1.0, 0.0]},",
                "          \"shape_hints\": {\"start\": [-1.0, -1.0, 0.0], \"end\": [1.0, 1.0, 0.0]} ",
                "        },",
                "        {",
                "          \"stable_id\": \"line_b\",",
                "          \"semantic_name\": \"line_b\",",
                "          \"class_name\": \"Line\",",
                "          \"semantic_class\": \"line\",",
                "          \"visible\": true,",
                "          \"bounds\": {\"min\": [-1.0, -1.0, 0.0], \"max\": [1.0, 1.0, 0.0]},",
                "          \"shape_hints\": {\"start\": [-1.0, 1.0, 0.0], \"end\": [1.0, -1.0, 0.0]} ",
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
