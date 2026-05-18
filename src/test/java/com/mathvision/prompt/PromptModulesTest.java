package com.mathvision.prompt;

import com.mathvision.util.TargetDescriptionBuilder;
import com.mathvision.util.TextHealthDiagnostics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptModulesTest {

    @Test
    void codeEvaluationPromptsMentionSemanticPlacementChecks() {
        String prompt = codeEvaluationSystemPrompt("Triangle Angles", "Demo", "manim");

        assertTrue(prompt.contains("semantically wrong placements"));
        assertTrue(prompt.contains("angle arcs"));
        assertTrue(prompt.contains("labels attached to the wrong point or segment"));
        assertTrue(prompt.contains("np.array(...)"));
    }

    @Test
    void codeEvaluationPromptsKeepApiWhitelistWarningsNonBlocking() {
        String manimPrompt = codeEvaluationSystemPrompt("Triangle", "Demo", "manim");
        String geogebraPrompt = codeEvaluationSystemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimPrompt.contains("API whitelist warning policy"));
        assertTrue(manimPrompt.contains("Report those findings as `warn`"));
        assertTrue(manimPrompt.contains("keep them out of `blocking_issues`"));
        assertTrue(geogebraPrompt.contains("API whitelist warning policy"));
        assertTrue(geogebraPrompt.contains("do not set `approved_for_render=false` solely because of them"));
    }

    @Test
    void conceptGraphPromptFramesMotionDrivenTeachingDag() {
        String prompt = ExplorationPrompts.buildConceptGraphFixedContextPrompt("Demo")
                + ExplorationPrompts.buildConceptGraphRulesPrompt();

        assertTrue(prompt.contains("motion-driven teaching DAG"));
        assertTrue(prompt.contains("Start by building a concrete visual situation"));
        assertTrue(prompt.contains("reveal the relationship, invariant, or pattern the concept captures"));
        assertTrue(prompt.contains("setup, motion reveal, and formal naming separate"));
        assertTrue(prompt.contains("Prioritize movable visual elements over text"));
        assertTrue(prompt.contains("concept, observation, construction, derivation, conclusion"));
    }

    @Test
    void problemGraphPromptFramesMotionDrivenSolvingDag() {
        String prompt = ExplorationPrompts.buildProblemGraphFixedContextPrompt("Demo")
                + ExplorationPrompts.buildProblemGraphRulesPrompt();

        assertTrue(prompt.contains("Start by building the problem situation as a concrete visual setup"));
        assertTrue(prompt.contains("reveal what quantity, relation, or target the problem is asking about"));
        assertTrue(prompt.contains("Prioritize movable problem quantities and visual elements over text"));
        assertTrue(prompt.contains("Each node must be one atomic solving beat"));
        assertTrue(prompt.contains("Setting up the problem situation and revealing the question's target through motion are separate atomic beats"));
        assertTrue(prompt.contains("Do not bundle multiple hidden reasoning moves into one node"));
        assertTrue(prompt.contains("problem, observation, construction, derivation, conclusion"));
    }

    @Test
    void stageZeroAsciiRulesDoNotPolluteTheirOwnPrompts() {
        String conceptPrompt = ExplorationPrompts.buildConceptGraphRulesPrompt();
        String problemPrompt = ExplorationPrompts.buildProblemGraphRulesPrompt();

        assertTrue(isAscii(SystemPrompts.ASCII_TEXT_RULES));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("U+2019 -> `'`"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("U+2014 -> `-`"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("U+2260 -> `!=`"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("`PB' - a`"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("`P_test != P_min`"));
        assertTrue(isAscii(conceptPrompt));
        assertTrue(isAscii(problemPrompt));
        assertFalse(TextHealthDiagnostics.inspect(conceptPrompt).suspicious());
        assertFalse(TextHealthDiagnostics.inspect(problemPrompt).suspicious());
    }

    @Test
    void codeReviewAndRevisionPromptsMentionPlacementCorrectness() {
        String reviewPrompt = CodeEvaluationPrompts.reviewUserPrompt(
                "DemoScene",
                "{\"scenes\":[]}",
                "{}",
                "from manim import *");
        String revisionPrompt = CodeEvaluationPrompts.revisionUserPrompt(
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
    void storyboardRulesPreferObjectRegistryOverScenePatchCoordinates() {
        String authorityRules = SystemPrompts.STORYBOARD_AUTHORITY_RULES;
        String referenceRules = SystemPrompts.STORYBOARD_REFERENCE_RULES;
        String manimCodegenPrompt = codeGenerationSystemPrompt("Shortest path", "Demo", "manim");
        String manimScenePrompt = CodeGenerationPrompts.manimSceneCodeUserPrompt(
                "{\"scene_id\":\"scene_1\"}", "scene_1", 0, 1);

        assertTrue(authorityRules.contains("`object_registry` as the canonical authority"));
        assertTrue(authorityRules.contains("scene `entering_objects`, `persistent_objects`, and `exiting_objects` as per-scene state patches"));
        assertTrue(authorityRules.contains("`notes_for_codegen`"));
        assertTrue(authorityRules.contains("hard semantic requirements"));
        assertTrue(authorityRules.contains("Do not treat scene-level `placement.x/y/z.value`, `min`, or `max` as a hard geometric constraint"));
        assertTrue(referenceRules.contains("prefer object_registry dependency facts over scene patch placement/style details"));
        assertTrue(manimCodegenPrompt.contains("never hardcode a coordinate copied from placement"));
        assertTrue(manimCodegenPrompt.contains("scene `notes_for_codegen`"));
        assertTrue(manimCodegenPrompt.contains("mandatory scene-level implementation constraint"));
        assertTrue(manimScenePrompt.contains("Do not instantiate it from hardcoded placement coordinates"));
        assertTrue(manimScenePrompt.contains("Treat `notes_for_codegen` as mandatory"));
    }

    @Test
    void codeEvaluationPromptsForbidTreatingScenePlacementAsHardConstraint() {
        String manimReviewPrompt = CodeEvaluationPrompts.reviewUserPrompt(
                "DemoScene",
                "{\"scenes\":[]}",
                "{}",
                "from manim import *");
        String geogebraReviewPrompt = CodeEvaluationPrompts.reviewUserPrompt(
                "DemoFigure",
                "{\"scenes\":[]}",
                "{}",
                "A=(0,0)",
                "geogebra");

        assertTrue(manimReviewPrompt.contains("use object_registry dependency facts as the semantic authority"));
        assertTrue(manimReviewPrompt.contains("Never call a scene placement coordinate such as `x.value` or `y.value` a storyboard hard constraint"));
        assertTrue(manimReviewPrompt.contains("verify the implementation by calculating the derived coordinates from its dependencies"));
        assertTrue(manimReviewPrompt.contains("direct numeric coordinates are acceptable only when they match fixed source geometry"));
        assertTrue(manimReviewPrompt.contains("notes_for_codegen"));
        assertTrue(geogebraReviewPrompt.contains("use object_registry dependency facts as the semantic authority"));
        assertTrue(geogebraReviewPrompt.contains("Never call a scene placement coordinate such as `x.value` or `y.value` a storyboard hard constraint"));
        assertTrue(geogebraReviewPrompt.contains("verify the implementation by calculating the derived coordinates from its dependencies"));
        assertTrue(geogebraReviewPrompt.contains("direct numeric coordinates are acceptable only when they match fixed source geometry"));
        assertTrue(geogebraReviewPrompt.contains("notes_for_codegen"));
    }

    @Test
    void visualAndNarrativePromptsPreserveMotionFirstTeachingIntent() {
        String manimVisualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "manim", null)
                + VisualDesignPrompts.buildRulesPrompt("manim");
        String geogebraVisualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null)
                + VisualDesignPrompts.buildRulesPrompt("geogebra");
        String narrativePrompt = narrativeSystemPrompt("Triangle", "Demo", "manim");

        assertFalse(manimVisualPrompt.contains("motion is not mandatory"));
        assertTrue(manimVisualPrompt.contains("favor motion for meaning-carrying elements"));
        assertTrue(manimVisualPrompt.contains("prefer moving, transforming, dragging, sweeping, or restyling existing elements over adding explanatory text"));
        assertTrue(geogebraVisualPrompt.contains("draggable, constrained, or movable construction elements over text-heavy explanation"));
        assertTrue(narrativePrompt.contains("motion-first visual-action teaching intent"));
        assertTrue(narrativePrompt.contains("do not turn a movable reveal, construction, transform, or manipulation into a static text/formula-only explanation"));
    }

    @Test
    void promptsMentionThreeDPlanningAndOverlayRules() {
        String visualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Vector Field", "3D demo", "manim", null)
                + VisualDesignPrompts.buildRulesPrompt("manim");
        String narrativePrompt = narrativeSystemPrompt("Vector Field", "3D demo", "manim");
        String codegenPrompt = codeGenerationSystemPrompt("Vector Field", "3D demo", "manim");
        String reviewPrompt = codeEvaluationSystemPrompt("Vector Field", "3D demo", "manim");

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
        String manimPrompt = codeGenerationSystemPrompt("Triangle", "Demo", "manim");
        String geogebraPrompt = codeGenerationSystemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimPrompt.contains("Manim syntax reference manual:"));
        assertTrue(geogebraPrompt.contains("GeoGebra syntax reference manual:"));
        assertTrue(geogebraPrompt.contains("GeoGebra Classic"));
        assertTrue(geogebraPrompt.contains("Build from base objects to derived objects in a clear dependency chain."));
    }

    @Test
    void geogebraNarrativePromptIncludesStyleReferenceLikeManim() {
        String manimPrompt = narrativeSystemPrompt("Triangle", "Demo", "manim");
        String geogebraPrompt = narrativeSystemPrompt("Triangle", "Demo", "geogebra");

        assertTrue(manimPrompt.contains("Manim style reference:"));
        assertTrue(geogebraPrompt.contains("GeoGebra style reference:"));
        assertTrue(geogebraPrompt.contains("Allowed Color Inputs"));
        assertTrue(geogebraPrompt.contains("official GeoGebra color inputs"));
        assertTrue(manimPrompt.contains("visually distinct from their background"));
        assertTrue(geogebraPrompt.contains("yellow on white"));
    }

    @Test
    void geogebraPromptsStayFreeOfManimOnlyNarrativeContracts() {
        String manimNarrative = narrativeSystemPrompt("Triangle", "Demo", "manim");
        String geogebraNarrative = narrativeSystemPrompt("Triangle", "Demo", "geogebra");
        String geogebraVisual = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null)
                + VisualDesignPrompts.buildRulesPrompt("geogebra");

        assertTrue(manimNarrative.contains("Manim-specific storyboard validation rules"));
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
        String systemPrompt = codeGenerationSystemPrompt("Triangle", targetDescription, "geogebra");

        assertTrue(targetDescription.contains("interactive geometry construction"));
        assertFalse(targetDescription.contains("teaching animation"));
        assertTrue(systemPrompt.contains("Final construction target"));
        assertFalse(systemPrompt.contains("Final animation target"));
    }

    @Test
    void narrativePromptsRequireObjectReferencesToUseIdsOnly() {
        String visualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null)
                + VisualDesignPrompts.buildRulesPrompt("geogebra");
        String codegenSystemPrompt = codeGenerationSystemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"angle_in\",\"kind\":\"angle\",\"content\":\"angle between AP and l at P\"}]}]}",
                "geogebra");

        assertTrue(visualPrompt.contains("refer to that object by id only"));
        assertTrue(visualPrompt.contains("angle between AP and l at P"));
        assertTrue(codegenSystemPrompt.contains("treat those mentions as object ids only"));
        assertFalse(codegenPrompt.contains("treat those mentions as object ids only"));
    }

    @Test
    void narrativePromptsRequireConciseMathStyleIds() {
        String visualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null) + VisualDesignPrompts.buildRulesPrompt("geogebra");
        String codegenSystemPrompt = codeGenerationSystemPrompt("Triangle", "Demo", "geogebra");
        String codegenPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"aLabel\",\"kind\":\"label\",\"content\":\"A\"}]}]}",
                "geogebra");

        assertTrue(visualPrompt.contains("Keep object ids concise"));
        assertTrue(visualPrompt.contains("Follow GeoGebra naming conventions"));
        assertTrue(visualPrompt.contains("native names like `B'`"));
        assertTrue(codegenSystemPrompt.contains("naming source"));
        assertFalse(codegenPrompt.contains("naming source"));
    }

    @Test
    void promptsRequireHighContrastColorChoices() {
        String visualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null) + VisualDesignPrompts.buildRulesPrompt("geogebra");
        String narrativePrompt = narrativeSystemPrompt("Triangle", "Demo", "geogebra");
        String geogebraCodegenPrompt = codeGenerationSystemPrompt("Triangle", "Demo", "geogebra");
        String manimCodegenPrompt = codeGenerationSystemPrompt("Triangle", "Demo", "manim");

        assertTrue(visualPrompt.contains("visually distinct from their background"));
        assertTrue(visualPrompt.contains("yellow on white"));
        assertTrue(narrativePrompt.contains("visually distinct"));
        assertTrue(narrativePrompt.contains("yellow on white"));
        assertTrue(geogebraCodegenPrompt.contains("visually distinct from their background"));
        assertTrue(manimCodegenPrompt.contains("yellow on white"));
    }

    @Test
    void narrativePromptEnforcesStrictJsonLexicalRulesAcrossFields() {
        String visualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "manim", null) + VisualDesignPrompts.buildRulesPrompt("manim");

        assertTrue(visualPrompt.contains("JSON lexical contract"));
        assertTrue(visualPrompt.contains("Do not output markdown fences"));
        assertTrue(visualPrompt.contains("Do not output bare identifiers as JSON values"));
        assertTrue(visualPrompt.contains("Invalid: \"type\": create"));
        assertTrue(visualPrompt.contains("Valid: \"type\": \"create\""));
        assertTrue(visualPrompt.contains("Allowed unquoted literals are only numbers, true, false, and null"));
    }

    @Test
    void geogebraCodegenPromptsAvoidManimInstructionsAndAsciiConflict() {
        String storyboardPrompt = NarrativePrompts.storyboardCodegenPrompt(
                "{\"scenes\":[{\"entering_objects\":[{\"id\":\"B'\",\"kind\":\"point\",\"content\":\"reflected point\"}]}]}",
                "geogebra");
        String codegenPrompt = codeGenerationSystemPrompt("Triangle", "GeoGebra demo", "geogebra");

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
        String geogebraVisualPrompt = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null) + VisualDesignPrompts.buildRulesPrompt("geogebra");

        assertTrue(geogebraVisualPrompt.contains("Use `fixed_overlay` mainly for explanatory text"));
        assertTrue(geogebraVisualPrompt.contains("bullseye-style highlights"));
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
    void geogebraPromptsUseGeogebraRepairAndViewportRules() {
        String geogebraNarrative = narrativeSystemPrompt("Triangle", "Demo", "geogebra");
        String manimNarrative = narrativeSystemPrompt("Triangle", "Demo", "manim");
        String geogebraVisual = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null)
                + VisualDesignPrompts.buildRulesPrompt("geogebra");
        String geogebraRevision = CodeEvaluationPrompts.buildRevisionRulesPrompt("geogebra");
        String geogebraRenderFix = RenderFixPrompts.buildRulesPrompt("geogebra");

        assertTrue(geogebraNarrative.contains("Storyboard field guide for this GeoGebra repair pass"));
        assertFalse(geogebraNarrative.contains("Storyboard field guide for this repair pass"));
        assertTrue(manimNarrative.contains("Storyboard field guide for this repair pass"));
        assertTrue(geogebraVisual.contains("GeoGebra viewport rules"));
        assertTrue(geogebraRevision.contains("GeoGebra angle marker rules"));
        assertTrue(geogebraRevision.contains("Minimize auxiliary helper objects in generated code"));
        assertTrue(geogebraRenderFix.contains("Audit the ENTIRE command script"));
    }

    @Test
    void renderFixUserPromptStartsWithErrorTypeBeforeCodeContext() {
        String prompt = RenderFixPrompts.manimUserPrompt(
                "from manim import *\n\nclass Demo(Scene):\n    pass",
                "Traceback (most recent call last):\nValueError: invalid point data",
                "{\"scenes\":[]}",
                java.util.List.of(),
                null,
                null
        );

        assertTrue(prompt.startsWith("[CURRENT_REQUEST]\nManim render failure detected.\nError type: TYPE_VALUE"));
        assertTrue(prompt.contains("Primary error signature: ValueError: invalid point data"));
        assertTrue(prompt.indexOf("Error type: TYPE_VALUE") < prompt.indexOf("```python"));
        assertTrue(prompt.indexOf("Error summary:") < prompt.indexOf("```python"));
        assertTrue(prompt.contains("Treat the error summary as a routing hint"));
    }

    private String narrativeSystemPrompt(String targetConcept, String targetDescription, String outputTarget) {
        return NarrativePrompts.buildFixedContextPrompt(targetConcept, targetDescription, outputTarget)
                + NarrativePrompts.buildRulesPrompt(outputTarget);
    }

    private String codeGenerationSystemPrompt(String targetConcept, String targetDescription, String outputTarget) {
        return CodeGenerationPrompts.buildFixedContextPrompt(targetConcept, targetDescription, outputTarget, null)
                + CodeGenerationPrompts.buildRulesPrompt(outputTarget);
    }

    private String codeEvaluationSystemPrompt(String targetConcept, String targetDescription, String outputTarget) {
        return CodeEvaluationPrompts.buildReviewFixedContextPrompt(targetConcept, targetDescription, outputTarget)
                + CodeEvaluationPrompts.buildReviewRulesPrompt(outputTarget);
    }

    private boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
