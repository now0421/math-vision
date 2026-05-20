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
                constraint("geometry", "lies_on", "P", "river", null, "P must stay on the river"),
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

        assertTrue(summary.contains("B1(point): geometry/reflection_across"), summary);
        assertTrue(summary.contains("owners=[B1]"), summary);
        assertFalse(summary.contains("l(line): geometry/reflection_across"), summary);
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
        constraint.setDomain("geometry");
        constraint.setRelation("reflection_across");
        constraint.setRefs(Map.of("image", "B1", "source", "B", "mirror", "l"));
        constraint.setStrength("hard");
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
