package com.mathvision.node;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.Narrative;
import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.WorkflowKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardValidationNodeTest {

    @TempDir
    Path tempDir;

    @Test
    void passesWhenObjectsStayWithinFrameAndDoNotOverlap() {
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_MANIM);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(
                        registryObject("title", "text", "Main title", null),
                        registryObject("diagram", "region", "Main diagram", null)
                ),
                List.of(
                        scenePatch("title", boxPlacement("screen", -1.5, 1.5, 2.1, 2.8)),
                        scenePatch("diagram", boxPlacement("world", -1.2, 1.2, -1.0, 1.0))
                ));

        List<String> issues = node.validate(storyboard);

        assertTrue(issues.isEmpty(), () -> String.join("\n", issues));
    }

    @Test
    void reportsOffscreenLayoutWhenBoundsExceedFrame() {
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_MANIM);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(registryObject("title", "text", "Main title", null)),
                List.of(scenePatch("title", boxPlacement("screen", 6.9, 7.5, 2.0, 2.7))));

        List<String> issues = node.validate(storyboard);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("title"));
        assertTrue(issues.get(0).contains("extends outside the frame bounds"));
    }

    @Test
    void reportsTextOverlapUsingStoryboardBounds() {
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_MANIM);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(
                        registryObject("headline", "text", "Headline", null),
                        registryObject("subhead", "text", "Subhead", null)
                ),
                List.of(
                        scenePatch("headline", boxPlacement("screen", -1.4, 1.2, 1.8, 2.6)),
                        scenePatch("subhead", boxPlacement("screen", -0.9, 1.6, 2.1, 2.9))
                ));

        List<String> issues = node.validate(storyboard);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("headline"));
        assertTrue(issues.get(0).contains("subhead"));
        assertTrue(issues.get(0).contains("overlap"));
    }

    @Test
    void reportsNonTextOverlapWhenBoundsCollide() {
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_MANIM);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(
                        registryObject("left_box", "region", "Left region", null),
                        registryObject("right_box", "region", "Right region", null)
                ),
                List.of(
                        scenePatch("left_box", boxPlacement("world", -1.0, 1.0, -1.0, 1.0)),
                        scenePatch("right_box", boxPlacement("world", -0.5, 1.5, -0.5, 1.5))
                ));

        List<String> issues = node.validate(storyboard);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("left_box"));
        assertTrue(issues.get(0).contains("right_box"));
        assertTrue(issues.get(0).contains("overlap"));
    }

    @Test
    void ignoresAttachedLabelOverlapWithItsAnchorObject() {
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_MANIM);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(
                        registryObject("point_a", "point", "Point A", null),
                        registryObject("point_a_label", "label", "A", "point_a")
                ),
                List.of(
                        scenePatch("point_a", boxPlacement("world", -0.1, 0.1, -0.1, 0.1)),
                        scenePatch("point_a_label", boxPlacement("anchor", -0.15, 0.15, -0.05, 0.15))
                ));

        List<String> issues = node.validate(storyboard);

        assertTrue(issues.isEmpty(), () -> String.join("\n", issues));
    }

    @Test
    void ignoresAttachedLabelOverlapByPrefixStyleNamingWithoutAnchorId() {
        // Covers the "label_a" / "point_a" prefix pattern where no anchorId is set —
        // semanticStem must strip type prefixes from both sides so the stems match.
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_MANIM);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(
                        registryObject("point_a", "point", "Point A", null),
                        registryObject("label_a", "label", "A", null)  // no anchorId, relies on stem matching
                ),
                List.of(
                        scenePatch("point_a", boxPlacement("world", -0.1, 0.1, -0.1, 0.1)),
                        scenePatch("label_a", boxPlacement("world", -0.15, 0.15, -0.05, 0.15))
                ));

        List<String> issues = node.validate(storyboard);

        assertTrue(issues.isEmpty(), () -> String.join("\n", issues));
    }

    @Test
    void skipsOffscreenChecksForGeoGebraStoryboardValidation() {
        StoryboardValidationNode node = prepareNode(WorkflowConfig.OUTPUT_TARGET_GEOGEBRA);
        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(registryObject("title", "text", "Main title", null)),
                List.of(scenePatch("title", boxPlacement("screen", 6.9, 7.5, 2.0, 2.7))));

        List<String> issues = node.validate(storyboard);

        assertTrue(issues.isEmpty(), () -> String.join("\n", issues));
    }

    @Test
    void postWritesStoryboardValidationArtifactWithIssues() throws IOException {
        StoryboardValidationNode node = new StoryboardValidationNode();
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(WorkflowConfig.OUTPUT_TARGET_MANIM);

        Storyboard storyboard = buildSingleSceneStoryboard(
                List.of(registryObject("title", "text", "Main title", null)),
                List.of(scenePatch("title", boxPlacement("screen", 6.9, 7.5, 2.0, 2.7))));
        Narrative narrative = new Narrative("Demo concept", "Demo description", storyboard);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        ctx.put(WorkflowKeys.NARRATIVE, narrative);
        ctx.put(WorkflowKeys.OUTPUT_DIR, tempDir);

        Narrative prepNarrative = node.prep(ctx);
        Narrative resultNarrative = node.exec(prepNarrative);
        node.post(ctx, prepNarrative, resultNarrative);

        String reportJson = Files.readString(tempDir.resolve("3_storyboard_validation.json"));
        assertTrue(reportJson.contains("\"initial_issue_count\""));
        assertTrue(reportJson.contains("\"initial_issues\""));
        assertTrue(reportJson.contains("extends outside the frame bounds"));
    }

    private StoryboardValidationNode prepareNode(String outputTarget) {
        StoryboardValidationNode node = new StoryboardValidationNode();
        WorkflowConfig config = new WorkflowConfig();
        config.setOutputTarget(outputTarget);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(WorkflowKeys.CONFIG, config);
        node.prep(ctx);
        return node;
    }

    private Storyboard buildSingleSceneStoryboard(List<StoryboardObject> registryObjects,
                                                  List<StoryboardObject> enteringObjects) {
        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Layout validation");
        scene.setGoal("Validate static storyboard geometry.");
        scene.setNarration("Validate positions.");
        scene.setLayoutGoal("Keep everything readable.");
        scene.setSafeAreaPlan("Stay within the frame.");
        scene.setScreenOverlayPlan("No extra overlay.");
        scene.setEnteringObjects(new ArrayList<>(enteringObjects));
        scene.setPersistentObjects(new ArrayList<>());
        scene.setExitingObjects(new ArrayList<>());

        Storyboard storyboard = new Storyboard();
        storyboard.setContinuityPlan("Keep object ids stable.");
        storyboard.setObjectRegistry(new ArrayList<>(registryObjects));
        storyboard.setScenes(List.of(scene));
        return storyboard;
    }

    private StoryboardObject registryObject(String id,
                                            String kind,
                                            String content,
                                            String anchorId) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setContent(content);
        object.setAnchorId(anchorId);
        return object;
    }

    private StoryboardObject scenePatch(String id, StoryboardPlacement placement) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setPlacement(placement);
        return object;
    }

    private StoryboardPlacement boxPlacement(String coordinateSpace,
                                             double minX,
                                             double maxX,
                                             double minY,
                                             double maxY) {
        StoryboardPlacement placement = new StoryboardPlacement();
        placement.setCoordinateSpace(coordinateSpace);
        placement.setX(axis(minX, maxX));
        placement.setY(axis(minY, maxY));
        return placement;
    }

    private StoryboardPlacementAxis axis(double min, double max) {
        StoryboardPlacementAxis axis = new StoryboardPlacementAxis();
        axis.setMin(min);
        axis.setMax(max);
        return axis;
    }
}
