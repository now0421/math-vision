package com.mathvision.prompt;

import com.mathvision.model.Narrative.Storyboard;
import com.mathvision.model.Narrative.StoryboardAction;
import com.mathvision.model.Narrative.StoryboardConstraint;
import com.mathvision.model.Narrative.StoryboardCoordinateBounds;
import com.mathvision.model.Narrative.StoryboardCoordinateBoundsAxis;
import com.mathvision.model.Narrative.StoryboardObject;
import com.mathvision.model.Narrative.StoryboardPlacement;
import com.mathvision.model.Narrative.StoryboardPlacementAxis;
import com.mathvision.model.Narrative.StoryboardScene;
import com.mathvision.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardJsonBuilderTest {

    @Test
    void codegenJsonSeparatesRegistrySemanticsFromScenePatchPlacement() throws Exception {
        Storyboard storyboard = new Storyboard();

        StoryboardObject anchor = objectWithPlacement("A", "point", -3.0, 1.0);
        StoryboardObject segment = objectWithPlacement("ABprime", "segment", 0.0, 0.0);
        StoryboardObject line = objectWithPlacement("l", "line", 0.0, -1.0);
        StoryboardObject pmin = objectWithPlacement("Pmin", "point", 0.6, -1.0);
        pmin.setConstraints(List.of(constraint(
                "Pmin_intersection",
                "construction",
                "intersection_of",
                Map.of("point", "Pmin", "object_a", "ABprime", "object_b", "l"),
                Map.of(),
                "hard")));
        storyboard.setObjectRegistry(List.of(anchor, segment, line, pmin));

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Intersection");
        scene.setEnteringObjects(List.of(scenePatch("A", -3.0, 1.0), scenePatch("Pmin", 0.6, -1.0)));
        storyboard.setScenes(List.of(scene));

        JsonNode codegen = JsonUtils.mapper().readTree(StoryboardJsonBuilder.buildForCodegen(storyboard));
        assertTrue(codegen.toString().contains("\"id\":\"A\""));
        assertFalse(findObject(codegen.get("object_registry"), "A").has("placement"));
        assertFalse(findObject(codegen.get("object_registry"), "Pmin").has("placement"));
        assertTrue(findObject(codegen.get("object_registry"), "Pmin").has("constraints"));

        JsonNode pminPatch = findObject(codegen.get("scenes").get(0).get("entering_objects"), "Pmin");
        assertTrue(pminPatch.has("placement"));
        assertFalse(pminPatch.has("kind"));
        assertFalse(pminPatch.has("content"));
        assertFalse(pminPatch.has("constraints"));

        String sceneFixJson = StoryboardJsonBuilder.buildForSceneEvaluationFix(storyboard);
        JsonNode sceneFix = JsonUtils.mapper().readTree(sceneFixJson);
        assertFalse(sceneFixJson.contains("\n"));
        assertFalse(sceneFix.toString().contains("\"placement\""));
    }

    @Test
    void codegenJsonIncludesStructuredConstraintsAndUsesThemForDerivedPlacement() throws Exception {
        Storyboard storyboard = new Storyboard();

        StoryboardObject base = objectWithPlacement("A", "point", -3.0, 1.0);
        StoryboardObject reflected = objectWithPlacement("A_ref", "point", -3.0, -3.0);
        reflected.setConstraints(List.of(constraint(
                "A_ref_reflection",
                "construction",
                "reflection_across",
                Map.of("image", "A_ref", "source", "A", "mirror", "l"),
                Map.of(),
                "hard")));
        StoryboardObject line = objectWithPlacement("l", "line", 0.0, 0.0);
        storyboard.setObjectRegistry(List.of(base, line, reflected));

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Reflection");
        scene.setConstraints(List.of(constraint(
                "reflection_group",
                "metric",
                "equal_measure_group",
                Map.of("members", List.of("A_ref", "A"), "reference", "l"),
                Map.of("measure", "distance_to_line"),
                "hard")));
        scene.setEnteringObjects(List.of(scenePatch("A", -3.0, 1.0), scenePatch("A_ref", -3.0, -3.0)));
        storyboard.setScenes(List.of(scene));

        JsonNode codegen = JsonUtils.mapper().readTree(StoryboardJsonBuilder.buildForCodegen(storyboard));

        JsonNode reflectedNode = findObject(codegen.get("object_registry"), "A_ref");
        assertTrue(reflectedNode.has("constraints"));
        assertFalse(reflectedNode.has("placement"));
        assertTrue(codegen.get("scenes").get(0).has("constraints"));
        assertTrue(codegen.toString().contains("\"relation\":\"reflection_across\""));
    }

    @Test
    void codegenJsonOmitsEmptyScenePatchMetadataArrays() throws Exception {
        Storyboard storyboard = new Storyboard();
        storyboard.setObjectRegistry(List.of(objectWithPlacement("A", "point", -3.0, 1.0)));

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Clean patches");
        scene.setEnteringObjects(List.of(scenePatch("A", -3.0, 1.0)));
        storyboard.setScenes(List.of(scene));

        String codegenJson = StoryboardJsonBuilder.buildForCodegen(storyboard);

        assertFalse(codegenJson.contains("\"dependency_objects\""));
        assertFalse(codegenJson.contains("\"constraints\" : [ ]"));
        assertFalse(codegenJson.contains("\"geometry_constraints\" : [ ]"));
        assertFalse(codegenJson.contains("\"step_refs\" : [ ]"));
    }

    @Test
    void codegenJsonIncludesCoordinateBoundsWhenPresent() throws Exception {
        Storyboard storyboard = new Storyboard();
        StoryboardCoordinateBounds bounds = new StoryboardCoordinateBounds();
        bounds.setX(new StoryboardCoordinateBoundsAxis(-4.0, 5.0));
        bounds.setY(new StoryboardCoordinateBoundsAxis(-2.0, 3.0));
        bounds.setPadding(1.0);
        storyboard.setCoordinateBounds(bounds);
        storyboard.setObjectRegistry(List.of(objectWithPlacement("A", "point", -3.0, 1.0)));

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Coordinate bounds");
        scene.setEnteringObjects(List.of(scenePatch("A", -3.0, 1.0)));
        storyboard.setScenes(List.of(scene));

        JsonNode codegen = JsonUtils.mapper().readTree(StoryboardJsonBuilder.buildForCodegen(storyboard));

        JsonNode boundsNode = codegen.path("coordinate_bounds");
        assertFalse(boundsNode.has("coordinate_space"));
        assertEquals(-4.0, boundsNode.path("x").path("min").asDouble());
        assertEquals(5.0, boundsNode.path("x").path("max").asDouble());
        assertEquals(-2.0, boundsNode.path("y").path("min").asDouble());
        assertEquals(3.0, boundsNode.path("y").path("max").asDouble());
        assertEquals(1.0, boundsNode.path("padding").asDouble());
    }

    @Test
    void codegenJsonIncludesVoiceoverFieldsAndChineseContent() throws Exception {
        Storyboard storyboard = new Storyboard();
        StoryboardObject title = objectWithPlacement("title", "text", 0.0, 2.5);
        title.setContent("中文标题");
        storyboard.setObjectRegistry(List.of(title));

        StoryboardAction action = new StoryboardAction();
        action.setOrder(1);
        action.setType("write");
        action.setTargets(List.of("title"));
        action.setDescription("Write the title.");
        action.setVoiceoverText("现在写出中文标题。");
        action.setExpectedSeconds(2.5);

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("中文标题场景");
        scene.setEnteringObjects(List.of(scenePatch("title", 0.0, 2.5)));
        scene.setActions(List.of(action));
        storyboard.setScenes(List.of(scene));

        JsonNode codegen = JsonUtils.mapper().readTree(StoryboardJsonBuilder.buildForCodegen(storyboard));
        JsonNode actionNode = codegen.get("scenes").get(0).get("actions").get(0);

        assertTrue(codegen.toString().contains("中文标题"));
        assertTrue(actionNode.path("voiceover_text").asText().contains("中文标题"));
        assertEquals(2.5, actionNode.path("expected_seconds").asDouble());
    }

    @Test
    void geogebraCodegenJsonExcludesVoiceoverFields() throws Exception {
        Storyboard storyboard = new Storyboard();

        StoryboardAction action = new StoryboardAction();
        action.setOrder(1);
        action.setType("write");
        action.setTargets(List.of("title"));
        action.setDescription("Write the title.");
        action.setVoiceoverText("现在写出中文标题。");
        action.setExpectedSeconds(2.5);

        StoryboardScene scene = new StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("中文标题场景");
        scene.setActions(List.of(action));
        storyboard.setScenes(List.of(scene));

        String codegenJson = StoryboardJsonBuilder.buildForCodegen(storyboard, "geogebra");
        String sceneJson = StoryboardJsonBuilder.buildSceneForCodegen(scene, "geogebra");
        String layoutFixJson = StoryboardJsonBuilder.buildForSceneEvaluationFix(storyboard, "geogebra");

        assertFalse(codegenJson.contains("voiceover_text"));
        assertFalse(codegenJson.contains("expected_seconds"));
        assertFalse(sceneJson.contains("voiceover_text"));
        assertFalse(sceneJson.contains("expected_seconds"));
        assertFalse(layoutFixJson.contains("voiceover_text"));
        assertFalse(layoutFixJson.contains("expected_seconds"));
    }

    private static StoryboardObject objectWithPlacement(String id,
                                                        String kind,
                                                        double x,
                                                        double y) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setKind(kind);
        object.setPlacement(placement(x, y));
        return object;
    }

    private static StoryboardObject scenePatch(String id, double x, double y) {
        StoryboardObject object = new StoryboardObject();
        object.setId(id);
        object.setPlacement(placement(x, y));
        return object;
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

    private static StoryboardConstraint constraint(String id,
                                                   String domain,
                                                   String relation,
                                                   Map<String, Object> refs,
                                                   Map<String, Object> parameters,
                                                   String strength) {
        StoryboardConstraint constraint = new StoryboardConstraint();
        constraint.setId(id);
        constraint.setDomain(domain);
        constraint.setRelation(relation);
        constraint.setRefs(refs);
        constraint.setParameters(parameters);
        constraint.setStrength(strength);
        return constraint;
    }

    private static JsonNode findObject(JsonNode array, String id) {
        for (JsonNode object : array) {
            if (id.equals(object.path("id").asText())) {
                return object;
            }
        }
        throw new AssertionError("Missing object " + id);
    }
}
