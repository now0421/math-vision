package com.automanim.prompt;

import com.automanim.util.TargetDescriptionBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void geogebraWorkflowPromptsUseConstructionLanguageInsteadOfAnimationLanguage() {
        String targetDescription = TargetDescriptionBuilder.workflowTargetDescription(
                "Triangle",
                "Reflect B across l and connect A to B'",
                "Use reflection to turn the broken route into one straight construction.",
                true,
                "geogebra");
        String systemPrompt = CodeGenerationPrompts.systemPrompt("Triangle", targetDescription, "geogebra");

        assertTrue(targetDescription.contains("interactive geometry construction"));
        assertFalse(targetDescription.contains("teaching animation"));
        assertTrue(systemPrompt.contains("Final construction target"));
        assertFalse(systemPrompt.contains("Final animation target"));
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
    void narrativePromptsRequireConciseMathStyleIds() {
        String systemPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Triangle",
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"aLabel\",\"kind\":\"label\",\"content\":\"A\"}]}]}",
                "geogebra");

        assertTrue(systemPrompt.contains("Keep object ids concise"));
        assertTrue(systemPrompt.contains("Follow GeoGebra naming conventions"));
        assertTrue(systemPrompt.contains("native names like `B'`"));
        assertTrue(codegenPrompt.contains("naming source"));
    }

    @Test
    void promptsRequireHighContrastColorChoices() {
        String visualPrompt = VisualDesignPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String narrativePrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String geogebraCodegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String manimCodegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "manim");

        assertTrue(visualPrompt.contains("high-contrast"));
        assertTrue(visualPrompt.contains("yellow on white"));
        assertTrue(narrativePrompt.contains("visually distinct"));
        assertTrue(narrativePrompt.contains("yellow on white"));
        assertTrue(geogebraCodegenPrompt.contains("visually distinct from their background"));
        assertTrue(manimCodegenPrompt.contains("yellow on white"));
    }

    @Test
    void geogebraCodegenPromptsAvoidManimInstructionsAndAsciiConflict() {
        String storyboardPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Triangle",
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"B'\",\"kind\":\"point\",\"content\":\"reflected point\"}]}]}",
                "geogebra");
        String codegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "GeoGebra demo", "geogebra");

        assertTrue(storyboardPrompt.contains("GeoGebra code block"));
        assertFalse(storyboardPrompt.contains("Python code block"));
        assertFalse(storyboardPrompt.contains("ThreeDScene"));
        assertFalse(storyboardPrompt.contains("add_fixed_in_frame_mobjects"));
        assertTrue(codegenPrompt.contains("`B'`"));
        assertTrue(codegenPrompt.contains("`P_{opt}`"));
        assertFalse(codegenPrompt.contains("ASCII-only"));
    }

    @Test
    void geogebraNarrativePromptGuidesFixedOverlayTowardTextualOverlays() {
        String geogebraPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(geogebraPrompt.contains("Use `fixed_overlay` mainly for explanatory text"));
        assertTrue(geogebraPrompt.contains("bullseye-style highlights"));
    }
}
