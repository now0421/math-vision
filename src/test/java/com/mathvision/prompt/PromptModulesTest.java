package com.mathvision.prompt;

import com.mathvision.util.TargetDescriptionBuilder;
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
    void conceptGraphPromptFramesCompactTypedTeachingDag() {
        String prompt = ExplorationPrompts.conceptGraphSystemPrompt("Circle theorem", "Demo", 4, 1);

        assertTrue(prompt.contains("compact teaching DAG"));
        assertTrue(prompt.contains("5 to 9 strong beats"));
        assertTrue(prompt.contains("concept, observation, construction, derivation, conclusion"));
        assertTrue(prompt.contains("`is_foundation` is metadata only"));
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
        String codegenPrompt = CodeGenerationPrompts.systemPrompt("Vector Field", "3D demo", "manim");
        String reviewPrompt = CodeEvaluationPrompts.reviewSystemPrompt("Vector Field", "3D demo");

        assertTrue(visualPrompt.contains("scene_mode"));
        assertTrue(visualPrompt.contains("screen_overlay_plan"));
        assertTrue(narrativePrompt.contains("camera_plan"));
        assertTrue(narrativePrompt.contains("scene_mode"));
        assertTrue(codegenPrompt.contains("ThreeDScene"));
        assertTrue(codegenPrompt.contains("fixed overlays readable in screen space"));
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
        assertTrue(geogebraPrompt.contains("Build from base objects to derived objects in a clear dependency chain."));
    }

    @Test
    void geogebraNarrativePromptIncludesStyleReferenceLikeManim() {
        String manimPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "manim");
        String geogebraPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimPrompt.contains("Manim style reference:"));
        assertTrue(geogebraPrompt.contains("GeoGebra style reference:"));
        assertTrue(geogebraPrompt.contains("Allowed Color Inputs"));
        assertTrue(geogebraPrompt.contains("official GeoGebra color inputs"));
        assertTrue(manimPrompt.contains("visually distinct from their background"));
        assertTrue(geogebraPrompt.contains("yellow on white"));
    }

    @Test
    void geogebraPromptsStayFreeOfManimOnlyNarrativeContracts() {
        String manimNarrative = NarrativePrompts.systemPrompt("Triangle", "Demo", "manim");
        String geogebraNarrative = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String geogebraVisual = VisualDesignPrompts.systemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimNarrative.contains("Manim-specific storyboard rules"));
        assertFalse(geogebraNarrative.contains("Manim teaching philosophy"));
        assertFalse(geogebraNarrative.contains("create a separate label object"));
        assertFalse(geogebraVisual.contains("always_redraw"));
        assertFalse(geogebraVisual.contains("monospace fonts"));
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
        String codegenSystemPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Triangle",
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"angle_in\",\"kind\":\"angle\",\"content\":\"angle between AP and l at P\"}]}]}",
                "geogebra");

        assertTrue(systemPrompt.contains("refer to that object by id only"));
        assertTrue(systemPrompt.contains("angle between AP and l at P"));
        assertTrue(codegenSystemPrompt.contains("treat those mentions as object ids only"));
        assertFalse(codegenPrompt.contains("treat those mentions as object ids only"));
    }

    @Test
    void narrativePromptsRequireConciseMathStyleIds() {
        String systemPrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String codegenSystemPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "Triangle",
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"aLabel\",\"kind\":\"label\",\"content\":\"A\"}]}]}",
                "geogebra");

        assertTrue(systemPrompt.contains("Keep object ids concise"));
        assertTrue(systemPrompt.contains("Follow GeoGebra naming conventions"));
        assertTrue(systemPrompt.contains("native names like `B'`"));
        assertTrue(codegenSystemPrompt.contains("naming source"));
        assertFalse(codegenPrompt.contains("naming source"));
    }

    @Test
    void promptsRequireHighContrastColorChoices() {
        String visualPrompt = VisualDesignPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String narrativePrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String geogebraCodegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "geogebra");
        String manimCodegenPrompt = CodeGenerationPrompts.systemPrompt("Triangle", "Demo", "manim");

        assertTrue(visualPrompt.contains("visually distinct from their background"));
        assertTrue(visualPrompt.contains("yellow on white"));
        assertTrue(narrativePrompt.contains("visually distinct"));
        assertTrue(narrativePrompt.contains("yellow on white"));
        assertTrue(geogebraCodegenPrompt.contains("visually distinct from their background"));
        assertTrue(manimCodegenPrompt.contains("yellow on white"));
    }

    @Test
    void narrativePromptEnforcesStrictJsonLexicalRulesAcrossFields() {
        String narrativePrompt = NarrativePrompts.systemPrompt("Triangle", "Demo", "manim");

        assertTrue(narrativePrompt.contains("JSON lexical contract is strict"));
        assertTrue(narrativePrompt.contains("Do not output markdown fences"));
        assertTrue(narrativePrompt.contains("Do not output bare identifiers as JSON values"));
        assertTrue(narrativePrompt.contains("Invalid: \"type\": create"));
        assertTrue(narrativePrompt.contains("Valid: \"type\": \"create\""));
        assertTrue(narrativePrompt.contains("Allowed unquoted literals are only numbers, true, false, and null"));
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

    @Test
    void geogebraRenderFixPromptMentionsFullValidationPassAndAllFailures() {
        String prompt = RenderFixPrompts.geoGebraUserPrompt(
                "A = Point({1, 0})",
                "GeoGebra validation found 2 failing commands out of 3 after replaying the full script:\n"
                        + "- Command 1 returned false: SetFixed(A, true)\n"
                        + "- Command 3 returned false: SetConditionToShowObject(floorLine, inSegment)",
                "{\"scenes\":[]}",
                java.util.List.of()
        );

        assertTrue(prompt.contains("one full replay pass"));
        assertTrue(prompt.contains("all reported failures become valid in one pass"));
        assertTrue(prompt.contains("Validation failure details collected from that full pass"));
    }

    @Test
    void renderFixUserPromptStartsWithErrorTypeBeforeCodeContext() {
        String prompt = RenderFixPrompts.userPrompt(
                "from manim import *\n\nclass Demo(Scene):\n    pass",
                "Traceback (most recent call last):\nValueError: invalid point data",
                "{\"scenes\":[]}",
                java.util.List.of()
        );

        assertTrue(prompt.startsWith("Manim render failure detected.\nError type: TYPE_VALUE"));
        assertTrue(prompt.contains("Primary error signature: ValueError: invalid point data"));
        assertTrue(prompt.indexOf("Error type: TYPE_VALUE") < prompt.indexOf("```python"));
        assertTrue(prompt.indexOf("Error output:") < prompt.indexOf("```python"));
    }
}
