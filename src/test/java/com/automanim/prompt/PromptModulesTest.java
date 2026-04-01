package com.automanim.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptModulesTest {

    @Test
    void codeEvaluationPromptsMentionSemanticPlacementChecks() {
        String prompt = CodeEvaluationPrompts.reviewSystemPrompt("Triangle Angles", "Demo");

        assertTrue(prompt.contains("semantically wrong placements"));
        assertTrue(prompt.contains("angle arcs"));
        assertTrue(prompt.contains("labels attached to the wrong point or segment"));
    }

    @Test
    void codeReviewAndRevisionPromptsMentionPlacementCorrectness() {
        String reviewPrompt = CodeEvaluationPrompts.reviewUserPrompt(
                "Triangle Angles",
                "DemoScene",
                "{\"scenes\":[]}",
                "{}",
                "from manim import *");
        String revisionPrompt = CodeEvaluationPrompts.revisionUserPrompt(
                "Triangle Angles",
                "DemoScene",
                "{\"scenes\":[]}",
                "{}",
                "{}",
                "from manim import *");

        assertTrue(reviewPrompt.contains("correct spatial relationships"));
        assertTrue(revisionPrompt.contains("angle arcs"));
        assertTrue(revisionPrompt.contains("wrong geometry"));
    }

    @Test
    void promptsMentionThreeDPlanningAndOverlayRules() {
        String visualPrompt = VisualDesignPrompts.systemPrompt("Vector Field", "3D demo", "manim");
        String narrativePrompt = NarrativePrompts.systemPrompt("Vector Field", "3D demo", "manim");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Vector Field",
                "{\"scenes\":[{\"scene_mode\":\"3d\",\"camera_plan\":\"orbit\",\"screen_overlay_plan\":\"Keep title fixed\"}]}");
        String reviewPrompt = CodeEvaluationPrompts.reviewSystemPrompt("Vector Field", "3D demo");

        assertTrue(visualPrompt.contains("scene_mode"));
        assertTrue(visualPrompt.contains("screen_overlay_plan"));
        assertTrue(narrativePrompt.contains("camera_plan"));
        assertTrue(narrativePrompt.contains("scene_mode"));
        assertTrue(codegenPrompt.contains("ThreeDScene"));
        assertTrue(codegenPrompt.contains("add_fixed_in_frame_mobjects"));
        assertTrue(reviewPrompt.contains("3D scenes"));
        assertTrue(reviewPrompt.contains("fixed-in-frame overlays"));
    }

    @Test
    void geogebraCodegenPromptIncludesSyntaxManualLikeManim() {
        String manimPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "manim");
        String geogebraPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimPrompt.contains("Manim syntax reference manual:"));
        assertTrue(geogebraPrompt.contains("GeoGebra syntax reference manual:"));
        assertTrue(geogebraPrompt.contains("GeoGebra Classic"));
        assertTrue(geogebraPrompt.contains("Define the base objects first."));
    }

    @Test
    void geogebraNarrativePromptIncludesStyleReferenceLikeManim() {
        String manimPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "manim");
        String geogebraPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimPrompt.contains("Manim style reference:"));
        assertTrue(geogebraPrompt.contains("GeoGebra style reference:"));
        assertTrue(geogebraPrompt.contains("Allowed Color Inputs"));
        assertTrue(geogebraPrompt.contains("official `SetColor`-compatible inputs"));
        assertTrue(manimPrompt.contains("high-contrast"));
        assertTrue(geogebraPrompt.contains("YELLOW` on `WHITE"));
    }

    @Test
    void narrativePromptsRequireObjectReferencesToUseIdsOnly() {
        String systemPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Triangle",
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"angle_in\",\"kind\":\"angle\",\"content\":\"angle between AP and l at P\"}]}]}",
                "geogebra");

        assertTrue(systemPrompt.contains("refer to that object by id only"));
        assertTrue(systemPrompt.contains("angle between AP and l at P"));
        assertTrue(codegenPrompt.contains("treat those mentions as object ids only"));
    }

    @Test
    void narrativePromptsRequireCamelCaseIdsWithoutUnderscores() {
        String systemPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Triangle",
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"aLabel\",\"kind\":\"label\",\"content\":\"A\"}]}]}",
                "geogebra");

        assertTrue(systemPrompt.contains("never underscores"));
        assertTrue(systemPrompt.contains("camelCase"));
        assertTrue(codegenPrompt.contains("no underscores"));
        assertTrue(codegenPrompt.contains("camelCase"));
    }

    @Test
    void promptsRequireHighContrastColorChoices() {
        String visualPrompt = VisualDesignPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String narrativePrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String geogebraCodegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String manimCodegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "manim");

        assertTrue(visualPrompt.contains("strong visual contrast"));
        assertTrue(visualPrompt.contains("yellow text on a white panel"));
        assertTrue(narrativePrompt.contains("high-contrast"));
        assertTrue(narrativePrompt.contains("yellow text on white"));
        assertTrue(geogebraCodegenPrompt.contains("high-contrast against the background"));
        assertTrue(manimCodegenPrompt.contains("yellow on white"));
    }
}
