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
        String visualPrompt = VisualDesignPrompts.systemPrompt("Vector Field", "3D demo");
        String narrativePrompt = NarrativePrompts.systemPrompt("Vector Field", "3D demo");
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
}
