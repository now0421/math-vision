package com.mathvision.prompt;

import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemDiagram;
import com.mathvision.util.JsonUtils;
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
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("Identifier ASCII rules"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("backend identifiers ASCII-only"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("scene_id"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("object id"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("Do not apply ASCII-only cleanup to learner-facing text"));
        assertTrue(SystemPrompts.ASCII_TEXT_RULES.contains("object content shown on screen"));
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
    void codeFixPromptsPreserveOriginalManimSceneBaseClass() {
        String revisionRules = CodeEvaluationPrompts.buildRevisionRulesPrompt("manim");
        String renderRules = RenderFixPrompts.buildRulesPrompt("manim");
        String layoutRules = SceneEvaluationPrompts.buildLayoutFixRulesPrompt("manim");
        String revisionUserPrompt = CodeEvaluationPrompts.revisionUserPrompt(
                "MainScene",
                "{\"scenes\":[]}",
                "{}",
                "{}",
                "from manim import *\nclass MainScene(VoiceoverScene):\n    pass");
        String renderUserPrompt = RenderFixPrompts.manimUserPrompt(
                "from manim import *\nclass MainScene(VoiceoverScene):\n    pass",
                "ValueError: demo",
                "{\"scenes\":[]}",
                java.util.List.of(),
                null,
                null);
        String layoutUserPrompt = SceneEvaluationPrompts.manimLayoutFixUserPrompt(
                "{\"scenes\":[]}",
                "from manim import *\nclass MainScene(VoiceoverScene):\n    pass",
                "overlap",
                "{}",
                java.util.List.of());
        String geogebraRevisionRules = CodeEvaluationPrompts.buildRevisionRulesPrompt("geogebra");
        String geogebraRenderRules = RenderFixPrompts.buildRulesPrompt("geogebra");
        String geogebraLayoutRules = SceneEvaluationPrompts.buildLayoutFixRulesPrompt("geogebra");

        assertTrue(revisionRules.contains("Manim code-fix class inheritance rules"));
        assertTrue(renderRules.contains("Manim code-fix class inheritance rules"));
        assertTrue(layoutRules.contains("Manim code-fix class inheritance rules"));
        assertTrue(revisionRules.contains("class MainScene(VoiceoverScene):"));
        assertTrue(renderRules.contains("Do not switch between `Scene`, `VoiceoverScene`, `ThreeDScene`"));
        assertTrue(layoutRules.contains("fix camera or 3D issues inside `ThreeDScene`"));
        assertTrue(revisionUserPrompt.contains("Preserve the original `class MainScene(...)` base class exactly"));
        assertTrue(renderUserPrompt.contains("Preserve the original `class MainScene(...)` base class exactly"));
        assertTrue(layoutUserPrompt.contains("Preserve the original `class MainScene(...)` base class exactly"));
        assertFalse(geogebraRevisionRules.contains("Manim code-fix class inheritance rules"));
        assertFalse(geogebraRenderRules.contains("Manim code-fix class inheritance rules"));
        assertFalse(geogebraLayoutRules.contains("Manim code-fix class inheritance rules"));
    }

    @Test
    void storyboardRulesPreserveValidatedScenePlacement() {
        String authorityRules = SystemPrompts.STORYBOARD_AUTHORITY_RULES;
        String referenceRules = SystemPrompts.STORYBOARD_REFERENCE_RULES;
        String manimCodegenPrompt = codeGenerationSystemPrompt("Shortest path", "Demo", "manim");
        String manimScenePrompt = CodeGenerationPrompts.manimSceneCodeUserPrompt(
                "{\"scene_id\":\"scene_1\"}", "scene_1", 0, 1);

        assertTrue(authorityRules.contains("`object_registry` as the canonical authority"));
        assertTrue(authorityRules.contains("scene `entering_objects`, `persistent_objects`, and `exiting_objects` as per-scene state patches"));
        assertTrue(authorityRules.contains("`notes_for_codegen`"));
        assertTrue(authorityRules.contains("hard semantic requirements"));
        assertTrue(authorityRules.contains("Use scene-level `placement.x/y.value`, `min`, and `max` as preferred visual-state coordinates"));
        assertTrue(authorityRules.contains("use `placement.z` only when ProblemBundle scene_mode is 3d"));
        assertTrue(authorityRules.contains("adjust them minimally or move/scale the whole constrained group"));
        assertFalse(authorityRules.contains("Do not treat scene-level `placement.x/y.value`, `min`, or `max` as a hard geometric constraint"));
        assertTrue(referenceRules.contains("consider object_registry constraints together with scene patch placement/style details"));
        assertTrue(manimCodegenPrompt.contains("Use scene placement as the preferred initial visual state"));
        assertTrue(manimCodegenPrompt.contains("scene `notes_for_codegen`"));
        assertTrue(manimCodegenPrompt.contains("mandatory scene-level implementation constraint"));
        assertTrue(manimScenePrompt.contains("use scene placement as the preferred initial visual state"));
        assertTrue(manimScenePrompt.contains("adjust it if needed for safe layout"));
        assertTrue(manimScenePrompt.contains("Treat `notes_for_codegen` as mandatory"));
    }

    @Test
    void codeEvaluationPromptsUseValidatedScenePlacement() {
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

        assertTrue(manimReviewPrompt.contains("use object_registry constraints, scene placement/style, and notes_for_codegen together"));
        assertTrue(manimReviewPrompt.contains("scene placement/style is preferred visual-state input"));
        assertTrue(manimReviewPrompt.contains("may be adjusted to fix offscreen, overlap, readability"));
        assertFalse(manimReviewPrompt.contains("Never call a scene placement coordinate such as `x.value` or `y.value` a storyboard hard constraint"));
        assertTrue(manimReviewPrompt.contains("verify the implementation by calculating the derived coordinates from constraint refs"));
        assertTrue(manimReviewPrompt.contains("recognizing a native dependency-based construction"));
        assertTrue(manimReviewPrompt.contains("direct numeric coordinates are acceptable only when they match fixed source geometry"));
        assertTrue(manimReviewPrompt.contains("notes_for_codegen"));
        assertTrue(geogebraReviewPrompt.contains("use object_registry constraints, scene placement/style, and notes_for_codegen together"));
        assertTrue(geogebraReviewPrompt.contains("scene placement/style is preferred visual-state input"));
        assertTrue(geogebraReviewPrompt.contains("may be adjusted to fix offscreen, overlap, readability"));
        assertFalse(geogebraReviewPrompt.contains("Never call a scene placement coordinate such as `x.value` or `y.value` a storyboard hard constraint"));
        assertTrue(geogebraReviewPrompt.contains("verify the implementation by calculating the derived coordinates from constraint refs"));
        assertTrue(geogebraReviewPrompt.contains("recognizing a native dependency-based construction"));
        assertTrue(geogebraReviewPrompt.contains("direct numeric coordinates are acceptable only when they match fixed source geometry"));
        assertTrue(geogebraReviewPrompt.contains("notes_for_codegen"));
    }

    @Test
    void manimCodegenPromptsRequireStableStoryboardObjectStore() {
        String codegenPrompt = codeGenerationSystemPrompt("Shortest path", "Demo", "manim");
        String scenePrompt = CodeGenerationPrompts.manimSceneCodeUserPrompt(
                "{\"scene_id\":\"scene_2\"}", "scene_2", 1, 2);

        assertTrue(codegenPrompt.contains("self.objects[\"id\"] = mobject"));
        assertTrue(codegenPrompt.contains("never infer semantic identity from `self.mobjects[index]`"));
        assertTrue(codegenPrompt.contains("`self.mobjects` may be used only for non-semantic whole-scene operations"));
        assertTrue(codegenPrompt.contains("application owns imports, MainScene, construct(), and final assembly"));
        assertTrue(scenePrompt.contains("self.objects[\"id\"]"));
        assertTrue(scenePrompt.contains("Never retrieve semantic objects with `self.mobjects[index]`"));
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
    void visualFixedContextCarriesCompleteProblemBundleAndDiagramContract() {
        ProblemBundle bundle = new ProblemBundle();
        bundle.setId("triangle_setup");
        bundle.setTitle("Triangle setup");
        bundle.setInputMode("problem");
        bundle.setOutputTarget("manim");
        bundle.setSceneMode("2d");
        bundle.setStatement("Given triangle ABC, construct altitude AD.");

        ProblemDiagram diagram = new ProblemDiagram();
        diagram.setPresent(true);
        diagram.setSourceObserved(true);
        diagram.setDiagramDescription(JsonUtils.parseTree("{"
                + "\"overall_shape\":\"Triangle ABC with altitude AD.\","
                + "\"points\":{\"A\":{\"role\":\"vertex\",\"position\":\"top\"}},"
                + "\"segments\":[{\"name\":\"AD\",\"description\":\"altitude from A\"}]"
                + "}"));
        diagram.setUnknowns(java.util.List.of(JsonUtils.parseTree("{"
                + "\"name\":\"altitude AD\","
                + "\"description\":\"construct the altitude from A\""
                + "}")));
        diagram.setNormalizationNotes(java.util.List.of("Build triangle ABC before showing altitude AD."));
        bundle.setDiagram(diagram);

        String prompt = VisualDesignPrompts.buildFixedContextPrompt(
                bundle,
                "Design the setup",
                "manim",
                "");

        assertTrue(prompt.contains("ProblemBundle JSON (authoritative workflow input):"));
        assertTrue(prompt.contains("\"id\" : \"triangle_setup\""));
        assertTrue(prompt.contains("Field roles:"));
        assertTrue(prompt.contains("`statement` is the normalized human-readable problem or concept text"));
        assertTrue(prompt.contains("`diagram.diagram_description` is native JSON"));
        assertTrue(prompt.contains("Mandatory initial diagram contract:"));
        assertTrue(prompt.contains("Scene 1 must construct the source-observed initial problem diagram"));
        assertTrue(prompt.contains("Translate it into `new_objects`"));
        assertTrue(prompt.contains("\"overall_shape\" : \"Triangle ABC with altitude AD.\""));
        assertTrue(prompt.contains("Build triangle ABC before showing altitude AD."));
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
        assertTrue(reviewPrompt.contains("three_d_scene_required"));
        assertFalse(reviewPrompt.contains("fixed-in-frame overlays"));
    }

    @Test
    void chineseVisibleTextAndVoiceoverRulesAreBackendIsolated() {
        String manimVisual = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "manim", null)
                + VisualDesignPrompts.buildRulesPrompt("manim");
        String geogebraVisual = VisualDesignPrompts.buildFixedContextPrompt("Triangle", "Demo", "geogebra", null)
                + VisualDesignPrompts.buildRulesPrompt("geogebra");
        String manimCodegen = codeGenerationSystemPrompt("Triangle", "Demo", "manim");
        String geogebraCodegen = codeGenerationSystemPrompt("Triangle", "Demo", "geogebra");
        String geogebraReview = codeEvaluationSystemPrompt("Triangle", "Demo", "geogebra");
        String geogebraRenderFix = RenderFixPrompts.buildRulesPrompt("geogebra");
        String geogebraSceneFix = SceneEvaluationPrompts.buildLayoutFixRulesPrompt("geogebra");

        assertTrue(manimVisual.contains("Manim voiceover rules"));
        assertTrue(manimVisual.contains("voiceover_text"));
        assertTrue(manimCodegen.contains("VoiceoverScene"));
        assertTrue(manimCodegen.contains("GTTSService"));
        assertTrue(manimCodegen.contains("Microsoft YaHei"));
        assertTrue(geogebraVisual.contains("Learner-facing visible text rules"));
        assertTrue(geogebraCodegen.contains("Learner-facing visible text rules"));
        assertFalse(geogebraVisual.contains("voiceover_text"));
        assertFalse(geogebraCodegen.contains("voiceover_text"));
        assertFalse(geogebraReview.contains("VoiceoverScene"));
        assertFalse(geogebraRenderFix.contains("GTTSService"));
        assertFalse(geogebraSceneFix.contains("self.voiceover"));
        assertFalse(geogebraSceneFix.contains("manim_voiceover"));
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
    void placementEnrichmentPromptsRequireScenePatchPlacements() {
        String systemPrompt = NarrativePrompts.PLACEMENT_ENRICHMENT_SYSTEM_PROMPT;
        String userPrompt = NarrativePrompts.buildPlacementEnrichmentUserPrompt(
                "{\"object_registry\":[{\"id\":\"Bprime\"}],\"scenes\":[{\"entering_objects\":[{\"id\":\"Bprime\"}]}]}");

        assertTrue(systemPrompt.contains("placement_patches"));
        assertTrue(systemPrompt.contains("scene_id"));
        assertTrue(systemPrompt.contains("object_id"));
        assertTrue(systemPrompt.contains("entering_objects or persistent_objects"));
        assertTrue(systemPrompt.contains("do not return object_registry"));
        assertTrue(userPrompt.contains("Return only compact patches"));
        assertTrue(userPrompt.contains("do not return the full storyboard JSON"));
        assertTrue(userPrompt.contains("scene_id"));
        assertTrue(userPrompt.contains("object_id"));
        assertFalse(userPrompt.contains("Return the full storyboard JSON"));
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

        assertTrue(geogebraVisualPrompt.contains("Use `attachment/fixed_overlay` constraints mainly for explanatory text"));
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
    void sceneEvaluationPromptsPutSyntaxManualInFixedContext() {
        String manimFixedContext = SceneEvaluationPrompts.buildLayoutFixFixedContextPrompt(
                "Demo concept",
                "Demo description",
                "manim");
        String geogebraFixedContext = SceneEvaluationPrompts.buildLayoutFixFixedContextPrompt(
                "Demo concept",
                "Demo description",
                "geogebra");
        String manimRules = SceneEvaluationPrompts.buildLayoutFixRulesPrompt("manim");
        String geogebraRules = SceneEvaluationPrompts.buildLayoutFixRulesPrompt("geogebra");

        assertTrue(manimFixedContext.contains("Manim syntax reference manual:"));
        assertTrue(geogebraFixedContext.contains("GeoGebra syntax reference manual:"));
        assertTrue(manimFixedContext.contains("Current workflow stage: Stage 8 / Scene Evaluation Fix"));
        assertTrue(geogebraFixedContext.contains("Current workflow stage: Stage 8 / Scene Evaluation Fix"));
        assertFalse(manimFixedContext.contains("ProblemBundle JSON"));
        assertFalse(geogebraFixedContext.contains("ProblemBundle JSON"));
        assertFalse(manimFixedContext.contains("Demo concept"));
        assertFalse(manimFixedContext.contains("Demo description"));
        assertFalse(manimRules.contains("Manim syntax reference manual:"));
        assertFalse(geogebraRules.contains("GeoGebra syntax reference manual:"));
        assertTrue(manimRules.contains("storyboard `safe_area_plan` and `layout_goal` are useful hints"));
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
        String storyboardJson = "{\"scenes\":[{\"scene_id\":\"s1\"}]}";
        String prompt = RenderFixPrompts.manimUserPrompt(
                "from manim import *\n\nclass Demo(Scene):\n    pass",
                "Traceback (most recent call last):\nValueError: invalid point data",
                storyboardJson,
                java.util.List.of(),
                null,
                null
        );

        assertTrue(prompt.startsWith("[CURRENT_REQUEST]\nManim render failure detected.\nError type: TYPE_VALUE"));
        assertTrue(prompt.contains("Primary error signature: ValueError: invalid point data"));
        assertTrue(prompt.indexOf("Error type: TYPE_VALUE") < prompt.indexOf("Detailed render error context:"));
        assertTrue(prompt.indexOf("Detailed render error context:") < prompt.indexOf("```python"));
        assertTrue(prompt.indexOf("```python") < prompt.indexOf("Compact storyboard JSON"));
        assertTrue(prompt.contains("The detailed render error context is the primary repair evidence"));
        assertFalse(prompt.contains("Treat the error summary as a routing hint"));

        String fixedContextPrompt = RenderFixPrompts.buildFixedContextPrompt(
                "Demo",
                "Repair render",
                "manim",
                storyboardJson);
        assertTrue(fixedContextPrompt.contains("Compact storyboard JSON (fixed reference context"));

        String promptWithoutInlineStoryboard = RenderFixPrompts.manimUserPrompt(
                "from manim import *",
                "ValueError: invalid point data",
                StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON,
                java.util.List.of(),
                null,
                null
        );
        assertTrue(promptWithoutInlineStoryboard.contains(
                "Storyboard reference context, if available, is provided in the fixed context message."));
    }

    @Test
    void renderAndSceneFixPromptsUseFlexibleScenePlacement() {
        String manimRenderRules = RenderFixPrompts.buildRulesPrompt("manim");
        String geogebraRenderRules = RenderFixPrompts.buildRulesPrompt("geogebra");
        String manimRenderUserPrompt = RenderFixPrompts.manimUserPrompt(
                "from manim import *",
                "ValueError: demo",
                "{\"scenes\":[{\"scene_id\":\"s1\"}]}",
                java.util.List.of(),
                null,
                null);
        String manimLayoutUserPrompt = SceneEvaluationPrompts.manimLayoutFixUserPrompt(
                "{\"scenes\":[]}",
                "from manim import *",
                "offscreen",
                "{}",
                java.util.List.of());

        assertTrue(manimRenderRules.contains("Treat storyboard scene placement as preferred layout input"));
        assertTrue(manimRenderRules.contains("adjust it when needed to fix runtime failures"));
        assertTrue(manimRenderRules.contains("one uniform screen scale"));
        assertTrue(manimRenderRules.contains("convert storyboard radii/metric lengths"));
        assertTrue(geogebraRenderRules.contains("Treat storyboard scene placement as preferred layout input"));
        assertTrue(manimRenderUserPrompt.contains("preferred scene placement for non-derived objects"));
        assertTrue(manimRenderUserPrompt.contains("preferred scene placement"));
        assertTrue(manimLayoutUserPrompt.contains("dependency semantics and scene intent"));
        assertTrue(manimLayoutUserPrompt.contains("placement omitted to avoid biasing layout repair"));
        assertFalse(manimLayoutUserPrompt.contains("derived-object placements are intentionally omitted"));
    }

    @Test
    void codegenAndEvaluationPromptsMentionArcSweepAndRightAngleConstraints() {
        String manimScenePrompt = CodeGenerationPrompts.manimSceneCodeUserPrompt(
                "{\"scene_id\":\"scene_1\"}", "scene_1", 0, 1);
        String geogebraScenePrompt = CodeGenerationPrompts.geoGebraSceneCodeUserPrompt(
                "{\"scene_id\":\"scene_1\"}", "Scene 1", 0, 1);
        String manimReviewPrompt = codeEvaluationSystemPrompt("Angles", "Demo", "manim");
        String geogebraReviewPrompt = codeEvaluationSystemPrompt("Angles", "Demo", "geogebra");

        assertTrue(manimScenePrompt.contains("arc_sweep"));
        assertTrue(manimScenePrompt.contains("right_angle_at"));
        assertTrue(manimScenePrompt.contains("ordered boundary refs"));
        assertTrue(manimScenePrompt.contains("self.world_radius(r)"));
        assertTrue(manimScenePrompt.contains("uniform unit scale"));
        assertTrue(geogebraScenePrompt.contains("arc_sweep"));
        assertTrue(geogebraScenePrompt.contains("right_angle_at"));
        assertTrue(geogebraScenePrompt.contains("sector, direction, and side"));
        assertTrue(manimReviewPrompt.contains("right_angle_at"));
        assertTrue(manimReviewPrompt.contains("arc_sweep"));
        assertTrue(manimReviewPrompt.contains("uniform x/y unit scale"));
        assertTrue(manimReviewPrompt.contains("Circle(radius=...)"));
        assertTrue(geogebraReviewPrompt.contains("right_angle_at"));
        assertTrue(geogebraReviewPrompt.contains("arc_sweep"));
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
