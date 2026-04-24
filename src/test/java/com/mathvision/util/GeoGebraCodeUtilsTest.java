package com.mathvision.util;

import com.mathvision.model.Narrative;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoGebraCodeUtilsTest {

    @Test
    void extractCode_extractsFromGeoGebraBlock() {
        String response = "```geogebra\nA = (0, 0)\nB = (4, 0)\n```";
        assertEquals("A = (0, 0)\nB = (4, 0)", GeoGebraCodeUtils.extractCode(response));
    }

    @Test
    void extractCommands_stripsBlankLinesAndComments() {
        List<String> commands = GeoGebraCodeUtils.extractCommands(String.join("\n",
                "",
                "# comment",
                "A = (0, 0)",
                "// another comment",
                "B = (4, 0)"
        ));

        assertEquals(List.of("A = (0, 0)", "B = (4, 0)"), commands);
    }

    @Test
    void validateGeoGebraRules_detectsFullWidthPunctuationOnly() {
        String code = String.join("\n",
                "point = Intersect(A\uFF0CB)",
                "B = (4, 0)");

        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("full-width punctuation")));
        assertFalse(violations.stream().anyMatch(v -> v.contains("non-ASCII")));
    }

    @Test
    void validateGeoGebraRules_detectsMultipleCommandsOnOneLine() {
        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules("A = (0, 0); B = (4, 0)");
        assertTrue(violations.stream().anyMatch(v -> v.contains("multiple commands on one line")));
    }

    @Test
    void validateGeoGebraRules_detectsUndocumentedCommand() {
        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules("triangle = Triangle(A, B, C)");

        assertTrue(violations.stream().anyMatch(v -> v.contains("undocumented GeoGebra command")
                && v.contains("Triangle()")));
    }

    @Test
    void validateGeoGebraRules_allowsDocumentedCommandsAndIgnoresQuotedText() {
        String code = String.join("\n",
                "L = Line(A, B)",
                "reflection = Reflect(A, L)",
                "SetCaption(L, \"Triangle(A, B, C) is just label text\")");

        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules(code);

        assertTrue(violations.isEmpty());
    }

    @Test
    void validateGeoGebraRules_allowsQuotedExecutableText() {
        List<String> violations = GeoGebraCodeUtils.validateGeoGebraRules(
                "SetCaption(L, \"minimum = 2\")");
        assertTrue(violations.isEmpty());
    }

    @Test
    void looksLikeCommandBlock_requiresMostlyCommandLikeLines() {
        assertTrue(GeoGebraCodeUtils.looksLikeCommandBlock(String.join("\n",
                "A = (0, 0)",
                "B = (4, 0)",
                "lineAB = Line(A, B)")));

        assertFalse(GeoGebraCodeUtils.looksLikeCommandBlock(String.join("\n",
                "Here is the construction:",
                "A = (0, 0)",
                "Please render it nicely.")));
    }

    @Test
    void validateFull_passesReasonableGeoGebraScript() {
        String code = String.join("\n",
                "l: y = 0",
                "A = (-4, 3)",
                "B = (4, 2)",
                "B1 = Reflect(B, l)",
                "g = Line(A, B1)",
                "Q = Intersect(g, l)");

        List<String> violations = GeoGebraCodeUtils.validateFull(code);

        assertTrue(violations.isEmpty());
    }

    @Test
    void enrichWithSceneButtons_appendsRoundTrippableSceneDirectives() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();

        Narrative.StoryboardScene scene1 = new Narrative.StoryboardScene();
        scene1.setSceneId("scene_1");
        scene1.setTitle("Set Up");
        Narrative.StoryboardObject pointA = new Narrative.StoryboardObject();
        pointA.setId("point_A");
        Narrative.StoryboardObject pointB = new Narrative.StoryboardObject();
        pointB.setId("point_B");
        scene1.setEnteringObjects(List.of(pointA, pointB));

        Narrative.StoryboardScene scene2 = new Narrative.StoryboardScene();
        scene2.setSceneId("scene_2");
        scene2.setTitle("Reveal");
        Narrative.StoryboardObject persistA = new Narrative.StoryboardObject();
        persistA.setId("point_A");
        Narrative.StoryboardObject persistB = new Narrative.StoryboardObject();
        persistB.setId("point_B");
        scene2.setPersistentObjects(List.of(persistA, persistB));
        Narrative.StoryboardObject helper = new Narrative.StoryboardObject();
        helper.setId("helper_line");
        scene2.setEnteringObjects(List.of(helper));
        Narrative.StoryboardObject exitB = new Narrative.StoryboardObject();
        exitB.setId("point_B");
        scene2.setExitingObjects(List.of(exitB));

        storyboard.setScenes(List.of(scene1, scene2));

        String enriched = GeoGebraCodeUtils.enrichWithSceneButtons(String.join("\n",
                "point_A = (0, 0)",
                "point_B = (4, 0)",
                "helper_line = Line(point_A, point_B)"), storyboard);

        List<GeoGebraCodeUtils.SceneDirective> directives = GeoGebraCodeUtils.extractSceneDirectives(enriched);

        assertEquals(2, directives.size());
        assertEquals("scene_1", directives.get(0).id);
        assertEquals("Set Up", directives.get(0).title);
        assertEquals(List.of("point_A", "point_B"), directives.get(0).show);
        assertEquals(List.of("helper_line"), directives.get(0).hide);
        assertEquals("scene_2", directives.get(1).id);
        assertEquals("Reveal", directives.get(1).title);
        assertEquals(List.of("point_A", "helper_line"), directives.get(1).show);
        assertEquals(List.of("point_B"), directives.get(1).hide);
    }

    @Test
    void enrichWithSceneButtons_preservesExistingSceneDirectivesFromCodeFix() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();

        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Storyboard Scene");
        Narrative.StoryboardObject storyboardPoint = new Narrative.StoryboardObject();
        storyboardPoint.setId("point_A");
        scene.setEnteringObjects(List.of(storyboardPoint));
        storyboard.setScenes(List.of(scene));

        String codeWithUpdatedDirectives = String.join("\n",
                "A = (0, 0)",
                "B = (4, 0)",
                "",
                "# AUTOGEN_SCENE_BUTTONS_BEGIN",
                "# @scene {\"id\":\"scene_1\",\"title\":\"Fixed Scene\",\"show\":[\"A\"],\"hide\":[\"B\"]}",
                "# AUTOGEN_SCENE_BUTTONS_END");

        String enriched = GeoGebraCodeUtils.enrichWithSceneButtons(codeWithUpdatedDirectives, storyboard);
        List<GeoGebraCodeUtils.SceneDirective> directives = GeoGebraCodeUtils.extractSceneDirectives(enriched);

        assertEquals(1, directives.size());
        assertEquals("scene_1", directives.get(0).id);
        assertEquals("Fixed Scene", directives.get(0).title);
        assertEquals(List.of("A"), directives.get(0).show);
        assertEquals(List.of("B"), directives.get(0).hide);
        assertFalse(enriched.contains("point_A"));
    }
}
