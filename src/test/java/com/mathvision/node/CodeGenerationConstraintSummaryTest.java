package com.mathvision.node;

import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.model.Narrative.StoryboardAction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGenerationConstraintSummaryTest {

    @Test
    void buildsConstraintSummaryFromObjectRegistry() {
        StoryboardScene scene = new StoryboardScene();
        scene.setPersistentObjects(List.of(
                stubObject("P", "point"),
                stubObject("river", "line")
        ));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("P", objectWithConstraints("P", "point",
                constraint("constraint", "lies_on", "P", "river", null, "P must stay on the river"),
                constraint("motion", "moves_on_object", "P", "river",
                        Map.of("range", "visible_line"), "P will slide along the river")));
        registry.put("river", stubStoryboardObject("river", "line"));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("lies_on"), "Should contain lies_on constraint");
        assertTrue(summary.contains("moves_on_object"), "Should contain moves_on_object constraint");
        assertTrue(summary.contains("P must stay on the river"), "Should contain reason");
        assertTrue(summary.contains("visible_line"), "Should contain range parameter");
        assertTrue(summary.startsWith("Constraint summary"), "Should start with header");
    }

    @Test
    void includesSceneLevelConstraints() {
        StoryboardScene scene = new StoryboardScene();
        scene.setConstraints(List.of(
                constraint("motion", "moves_on_object", "P", "river",
                        Map.of("range", List.of(-4.5, 4.5)), "P must stay on visible river segment")));
        scene.setPersistentObjects(List.of(stubObject("P", "point")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("P", stubStoryboardObject("P", "point"));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("moves_on_object"), "Should contain scene-level constraint");
        assertTrue(summary.contains("-4.5"), "Should contain range value");
        assertTrue(summary.contains("4.5"), "Should contain range value");
    }

    @Test
    void returnsEmptyForNoConstraints() {
        StoryboardScene scene = new StoryboardScene();
        scene.setPersistentObjects(List.of(stubObject("A", "point")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("A", stubStoryboardObject("A", "point"));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertEquals("", summary, "Should be empty when no constraints");
    }

    @Test
    void returnsEmptyForNullRegistry() {
        StoryboardScene scene = new StoryboardScene();
        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, null);
        assertEquals("", summary);
    }

    @Test
    void collectsObjectIdsFromActions() {
        StoryboardScene scene = new StoryboardScene();
        StoryboardAction action = new StoryboardAction();
        action.setOrder(1);
        action.setType("move");
        action.setTargets(List.of("P"));
        action.setDescription("Slide P");
        scene.setActions(List.of(action));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("P", objectWithConstraints("P", "point",
                constraint("motion", "moves_on_object", "P", "river",
                        Map.of("range", "visible_line"), "P slides along river")));
        registry.put("river", stubStoryboardObject("river", "line"));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("moves_on_object"), "Should find constraint from action target");
    }

    @Test
    void sceneLevelConstraintSummaryUsesCatalogOwnerRoles() {
        StoryboardScene scene = new StoryboardScene();
        scene.setConstraints(List.of(reflectionConstraint()));
        scene.setPersistentObjects(List.of(stubObject("B1", "point")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("B", stubStoryboardObject("B", "point"));
        registry.put("l", stubStoryboardObject("l", "line"));
        registry.put("B1", stubStoryboardObject("B1", "point"));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("B1(point): construction/reflection_across"), summary);
        assertTrue(summary.contains("owners=[B1]"), summary);
        assertFalse(summary.contains("l(line): construction/reflection_across"), summary);
    }

    @Test
    void summarizesAngularMarkerConstraintsWithDisambiguationParameters() {
        StoryboardScene scene = new StoryboardScene();
        scene.setPersistentObjects(List.of(stubObject("sweep", "arc_marker"), stubObject("right", "right_angle_marker")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("O", stubStoryboardObject("O", "point"));
        registry.put("OA", stubStoryboardObject("OA", "ray"));
        registry.put("OB", stubStoryboardObject("OB", "ray"));
        registry.put("sweep", objectWithConstraints("sweep", "arc_marker",
                angularConstraint("marker", "arc_sweep",
                        Map.of("arc", "sweep", "center", "O", "start_boundary", "OA", "end_boundary", "OB"),
                        Map.of("direction", "counterclockwise", "sector", "minor"),
                        "Sweep from OA to OB")));
        registry.put("right", objectWithConstraints("right", "right_angle_marker",
                angularConstraint("marker", "right_angle_at",
                        Map.of("marker", "right", "vertex", "O", "start_boundary", "OA", "end_boundary", "OB"),
                        Map.of("side_of_reference", "inside"),
                        "Right angle side is inside")));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("arc_sweep"), summary);
        assertTrue(summary.contains("right_angle_at"), summary);
        assertTrue(summary.contains("counterclockwise"), summary);
        assertTrue(summary.contains("side_of_reference=inside") || summary.contains("side_of_reference"), summary);
    }

    @Test
    void explicitPointPlacementIsNotTaggedCoordinateDerived() {
        StoryboardScene scene = new StoryboardScene();
        scene.setPersistentObjects(List.of(stubObject("P", "point")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("P", objectWithConstraints("P", "point",
                storyboardConstraint("placement", "point_at",
                        Map.of("point", "P"),
                        Map.of("coordinate", List.of(1, 2)),
                        "P has fixed coordinates")));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("P(point): placement/point_at"), summary);
        assertFalse(summary.contains("[coordinate-derived]"), summary);
        assertFalse(summary.contains("[motion-sensitive]"), summary);
    }

    @Test
    void dependencyBasedConstructionStillTaggedCoordinateDerived() {
        StoryboardScene scene = new StoryboardScene();
        scene.setPersistentObjects(List.of(stubObject("M", "point")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("A", stubStoryboardObject("A", "point"));
        registry.put("B", stubStoryboardObject("B", "point"));
        registry.put("M", objectWithConstraints("M", "point",
                storyboardConstraint("construction", "midpoint_of",
                        Map.of("point", "M", "endpoint_a", "A", "endpoint_b", "B"),
                        Map.of(),
                        "M is midpoint of AB")));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("M(point): construction/midpoint_of [coordinate-derived] [motion-sensitive]"), summary);
    }

    @Test
    void summarizesAbsoluteSideConstraintsWithoutIncidenceSemantics() {
        StoryboardScene scene = new StoryboardScene();
        scene.setPersistentObjects(List.of(stubObject("A", "point"), stubObject("l", "line")));

        Map<String, StoryboardObject> registry = new LinkedHashMap<>();
        registry.put("A", objectWithConstraints("A", "point",
                storyboardConstraint("constraint", "on_side_of",
                        Map.of("object", "A", "reference", "l"),
                        Map.of("side", "above"),
                        "A stays above l")));
        registry.put("l", stubStoryboardObject("l", "line"));

        String summary = CodeGenerationNode.buildSceneConstraintSummary(scene, registry);

        assertTrue(summary.contains("A(point): constraint/on_side_of"), summary);
        assertTrue(summary.contains("owners=[A]"), summary);
        assertTrue(summary.contains("dependencies=[l]"), summary);
        assertTrue(summary.contains("side=above"), summary);
    }

    // --- helpers ---

    private static StoryboardObject stubObject(String id, String kind) {
        StoryboardObject obj = new StoryboardObject();
        obj.setId(id);
        obj.setKind(kind);
        return obj;
    }

    private static StoryboardObject stubStoryboardObject(String id, String kind) {
        return stubObject(id, kind);
    }

    private static StoryboardObject objectWithConstraints(String id, String kind,
                                                           StoryboardConstraint... constraints) {
        StoryboardObject obj = stubObject(id, kind);
        obj.setConstraints(List.of(constraints));
        return obj;
    }

    private static StoryboardConstraint reflectionConstraint() {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setDomain("construction");
        constraint.setRelation("reflection_across");
        constraint.setRefs(Map.of("image", "B1", "source", "B", "mirror", "l"));
        constraint.setStrength("hard");
        return constraint;
    }

    private static StoryboardConstraint angularConstraint(String domain,
                                                          String relation,
                                                          Map<String, Object> refs,
                                                          Map<String, Object> parameters,
                                                          String reason) {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength("hard");
        constraint.setReason(reason);
        return constraint;
    }

    private static StoryboardConstraint storyboardConstraint(String domain,
                                                             String relation,
                                                             Map<String, Object> refs,
                                                             Map<String, Object> parameters,
                                                             String reason) {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength("hard");
        constraint.setReason(reason);
        return constraint;
    }

    private static StoryboardConstraint constraint(String domain, String relation,
                                                    String pointRef, String supportRef,
                                                    Map<String, Object> parameters,
                                                    String reason) {
        StoryboardConstraint c = new StoryboardConstraint();
        c.setDomain(domain);
        c.setRelation(relation);
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("point", pointRef);
        refs.put("support", supportRef);
        c.setRefs(refs);
        if (parameters != null) {
            c.setParameters(parameters);
        }
        c.setStrength("hard");
        c.setReason(reason);
        return c;
    }
}
