package com.mathvision.prompt;

/**
 * Prompts for Stage 3: code evaluation and revision.
 */
public final class CodeEvaluationPrompts {

    private static final String REVIEW_OUTPUT_SCHEMA =
            "Output format:\n"
                    + "Return a JSON object with this shape. Do not score anything:\n"
                    + "{\n"
                    + "  \"approved_for_render\": \"boolean, true only if every mandatory rule is pass or not_applicable and no blocking issue exists\",\n"
                    + "  \"rule_checks\": [\n"
                    + "    {\n"
                    + "      \"rule_id\": \"string, stable snake_case id from the checklist\",\n"
                    + "      \"requirement\": \"string, the concrete rule being checked\",\n"
                    + "      \"status\": \"pass | warn | fail | not_applicable\",\n"
                    + "      \"evidence\": \"string, cite concrete storyboard/code evidence; say why not_applicable when relevant\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"summary\": \"string, concise compliance summary against the storyboard and rules\",\n"
                    + "  \"strengths\": [\"string, specific strength that should be preserved\"],\n"
                    + "  \"blocking_issues\": [\"string, only failed mandatory checks that should block render\"],\n"
                    + "  \"revision_directives\": [\"string, concrete change request for each fail or warn\"]\n"
                    + "}\n\n"
                    + "Every fail must have a matching blocking issue and revision directive.\n"
                    + "Use warn for non-blocking risk with concrete evidence. Use not_applicable only when the storyboard/code makes the rule irrelevant.\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private static final String REVIEW_SYSTEM_MANIM =
            "You are a senior Manim code reviewer.\n"
                    + "Your job is NOT to debug runtime errors.\n"
                    + "Your primary job is rule-compliance inspection before render.\n"
                    + "Do not assign numeric quality scores. Instead, check each rule below as pass, warn, fail, or not_applicable using concrete storyboard/code evidence.\n\n"
                    + "The storyboard JSON is the source of truth.\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_FULL.replace("\n", "\n- ").trim() + "\n"
                    + "Mandatory Manim rule checklist:\n"
                    + "- `storyboard_execution`: learner-visible objects, scene/action order, entering/persistent/exiting lifecycle, and teaching goal are implemented from the storyboard, not merely mentioned in comments or captions.\n"
                    + "- `geometry_constraints`: reflection, symmetry, collinearity, intersections, equal lengths, constrained motion, dependency notes, and derived objects remain dependency-driven rather than freely moved.\n"
                    + "- `layout_and_hierarchy`: important content follows `layout_goal` and `safe_area_plan`, maintains one clear focus, uses opacity hierarchy, preserves empty overlay space, and avoids code-evident persistent crowding.\n"
                    + "- `continuity_and_identity`: existing storyboard ids/actions reuse the same mobjects where continuity matters, prefer transforms/restyles over redraws, and clean temporary annotations when their beat is done.\n"
                    + "- `pacing_and_narration`: important reveals have subtitle-ready beats, `self.add_subcaption(...)` or `subcaption=`, and enough `self.wait(...)` breathing room instead of stacked animations.\n"
                    + "- `text_readability`: `Text(...)`/`MarkupText(...)` use monospace fonts, on-screen text uses `font_size >= 18`, `.to_edge()` uses `buff >= 0.5`, long text width is constrained, and light cards have dark text.\n"
                    + "- `manim_code_hygiene`: code uses documented Manim APIs, shared color constants, `self.camera.background_color = BG`, no hardcoded hex colors inside scene methods, stable animation targets, and no unsafe empty `always_redraw` animation targets.\n"
                    + "- `angle_and_attachment`: angle markers use true shared vertices/rays with explicit quadrant/other_angle when needed, and labels attached to moving objects use an updater or `always_redraw(...)`.\n"
                    + "- `three_d_readability`: 3D storyboard scenes use `ThreeDScene`, apply explicit camera plan/orientation, and keep explanatory overlays fixed in frame or fixed orientation when camera motion would make them rotate away.\n"
                    + "- Specifically fail semantically wrong placements such as angle arcs on the wrong side, labels attached to the wrong point or segment, braces spanning the wrong expression, or highlights pointing at the wrong target.\n"
                    + "- For 3D scenes, check projected readability, camera clarity, and fixed-in-frame overlays.\n"
                    + "- A later geometry-based stage will inspect rendered frames for actual overlap/offscreen issues. Here, fail only when the code itself clearly violates the storyboard or these rules.\n"
                    + "- Treat density counts from static analysis as heuristics, not automatic failures, when the code uses staging, dimming, grouping, cleanup, or pauses that keep the frame readable.\n\n"
                    + REVIEW_OUTPUT_SCHEMA;

    private static final String REVIEW_SYSTEM_GEOGEBRA =
            "You are a senior GeoGebra construction reviewer.\n"
                    + "Your job is NOT to debug runtime errors unless they directly affect storyboard fidelity.\n"
                    + "Your primary job is rule-compliance inspection for a GeoGebra teaching construction before render.\n"
                    + "Do not assign numeric quality scores. Instead, check each rule below as pass, warn, fail, or not_applicable using concrete storyboard/code evidence.\n\n"
                    + "The storyboard JSON is the source of truth.\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA.replace("\n", "\n- ").trim() + "\n"
                    + "Mandatory GeoGebra rule checklist:\n"
                    + "- `storyboard_execution`: required learner-visible objects are actually constructed, not merely described by text, comments, or captions.\n"
                    + "- `visibility_progression`: scene-level visibility and highlight progression matches `entering_objects`, `persistent_objects`, `exiting_objects`, and `actions`.\n"
                    + "- `geometry_constraints`: reflections, symmetry, collinearity, intersections, equal lengths, grids, partitions, constrained points, and derived objects use dependency-safe documented constructions.\n"
                    + "- `object_identity`: object ids/names remain stable, helpers are not mistaken for storyboard objects, and redundant duplicates on the same endpoints are avoided.\n"
                    + "- `layout_and_readability`: coordinates, labels, style, contrast, and initial view follow `layout_goal`, `safe_area_plan`, and `screen_overlay_plan` where applicable.\n"
                    + "- `geogebra_syntax`: command names and syntax are documented in the attached GeoGebra manual, one executable command is used per line, and unsupported guessed overloads are not used.\n"
                    + "- `teaching_evidence`: result text or labels are supported by matching constructed geometry; no semantically wrong substitution such as drawing a border where a full grid was requested.\n"
                    + "- A later geometry-based stage will inspect rendered geometry for actual overlap/offscreen issues. Do not duplicate that stage.\n"
                    + "- GeoGebra is interactive, so do not over-penalize pure zoomability issues. Focus on likely initial-view readability and storyboard fidelity.\n\n"
                    + REVIEW_OUTPUT_SCHEMA;

    private static final String REVISION_SYSTEM_MANIM =
            "You are a Manim code revision specialist.\n"
                    + "You will receive storyboard JSON, static visual findings, a structured review, and the current code.\n"
                    + "Rewrite the full code so it is visually safer before render.\n"
                    + "Reduce clutter, preserve continuity with transforms, correct semantically wrong placements, keep 3D camera plans readable, and also fix common Python/Manim runtime mistakes.\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_FULL
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES + "\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + SystemPrompts.ANGLE_MARKER_RULES
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private static final String REVISION_SYSTEM_GEOGEBRA =
            "You are a GeoGebra command revision specialist.\n"
                    + "You will receive storyboard JSON, static visual findings, a structured review, and the current command script.\n"
                    + "Rewrite the full command script so it better aligns with the storyboard before render.\n"
                    + "Preserve dependency-safe geometry, object identities, scene visibility progression, and teaching intent.\n"
                    + "Fix storyboard misalignments such as missing constructions, incorrect scene visibility, incorrect substitutions for requested geometry, and captions unsupported by the actual construction.\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA_REPAIR
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES + "\n"
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private CodeEvaluationPrompts() {}

    public static String buildReviewRulesPrompt(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(
                    SystemPrompts.ensureGeoGebraSyntaxManual(REVIEW_SYSTEM_GEOGEBRA));
        }
        return SystemPrompts.buildRulesSection(REVIEW_SYSTEM_MANIM);
    }

    public static String buildReviewFixedContextPrompt(String targetConcept,
                                                       String targetDescription,
                                                       String outputTarget) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 3 / Code Evaluation",
                "Review " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra code" : "code")
                        + " for storyboard alignment, layout, continuity, pacing, and clutter risk",
                targetConcept,
                targetDescription,
                outputTarget
        ));
    }

    public static String reviewUserPrompt(String sceneName,
                                          String storyboardJson,
                                          String staticAnalysisJson,
                                          String generatedCode) {
        return reviewUserPrompt(sceneName, storyboardJson, staticAnalysisJson, generatedCode, "manim");
    }

    public static String reviewUserPrompt(String sceneName,
                                          String storyboardJson,
                                          String staticAnalysisJson,
                                          String generatedCode,
                                          String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildCurrentRequestSection(String.format(
                    "Figure name: %s\n\n"
                            + "Compact storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                            + "Static visual analysis:\n```json\n%s\n```\n\n"
                            + "GeoGebra command script to review:\n```geogebra\n%s\n```\n\n"
                            + "Check every mandatory GeoGebra rule before render.\n"
                            + "Focus on whether the actual construction, scene visibility progression, and teaching evidence comply with the storyboard.\n"
                            + "Return only the structured rule-compliance output.",
                    sceneName, storyboardJson, staticAnalysisJson, generatedCode));
        }
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Scene class name: %s\n\n"
                        + "Compact storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Manim code to review:\n```python\n%s\n```\n\n"
                        + "Check every mandatory Manim rule before render.\n"
                        + "Focus on storyboard execution, geometry constraints, continuity, pacing versus narration, 3D readability, fixed-in-frame overlays, correct spatial relationships, text readability, and code-evident clutter.\n"
                        + "Return only the structured rule-compliance output.",
                sceneName, storyboardJson, staticAnalysisJson, generatedCode));
    }

    public static String buildRevisionRulesPrompt(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(
                    SystemPrompts.ensureGeoGebraSyntaxManual(REVISION_SYSTEM_GEOGEBRA));
        }
        return SystemPrompts.buildRulesSection(
                SystemPrompts.ensureManimSyntaxManual(REVISION_SYSTEM_MANIM));
    }

    public static String buildRevisionFixedContextPrompt(String targetConcept,
                                                         String targetDescription,
                                                         String outputTarget) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 3 / Code Evaluation",
                "Revise " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra code" : "Manim code")
                        + " after code evaluation before render",
                targetConcept,
                targetDescription,
                outputTarget
        ));
    }

    public static String revisionUserPrompt(String sceneName,
                                            String storyboardJson,
                                            String staticAnalysisJson,
                                            String reviewJson,
                                            String generatedCode) {
        return revisionUserPrompt(
                sceneName,
                storyboardJson,
                staticAnalysisJson,
                reviewJson,
                generatedCode,
                "manim");
    }

    public static String revisionUserPrompt(String sceneName,
                                            String storyboardJson,
                                            String staticAnalysisJson,
                                            String reviewJson,
                                            String generatedCode,
                                            String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildCurrentRequestSection(String.format(
                    "Figure name: %s\n\n"
                            + "Compact storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                            + "Static visual analysis:\n```json\n%s\n```\n\n"
                            + "Structured code review:\n```json\n%s\n```\n\n"
                            + "Current GeoGebra command script:\n```geogebra\n%s\n```\n\n"
                            + "Rewrite the FULL command script to better match the storyboard, preserve dependency-safe geometry, correct scene visibility progression, and restore missing visual evidence requested by the storyboard.\n"
                            + "Preserve storyboard geometric invariants and the teaching goal.\n"
                            + "Use only command names and syntax forms documented in the attached GeoGebra syntax manual. Replace any undocumented command or guessed syntax with a documented equivalent.\n"
                            + "Return ONLY the full GeoGebra code block.",
                    sceneName, storyboardJson, staticAnalysisJson, reviewJson, generatedCode));
        }
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Scene class name: %s\n\n"
                        + "Compact storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Structured code review:\n```json\n%s\n```\n\n"
                        + "Current Manim code:\n```python\n%s\n```\n\n"
                        + "Rewrite the FULL code to reduce clutter, preserve continuity, correct semantically wrong placements such as angle arcs or labels attached to the wrong geometry, better match pacing to narration, and keep 3D overlays readable.\n"
                        + "Preserve any storyboard geometric invariants such as symmetry, reflection, collinearity, and intersection definitions while making layout safer.\n"
                        + "Also fix nearby Python/Manim runtime mistakes. Preserve the scene class name and teaching goal.\n"
                        + "Return ONLY the full Python code block.",
                sceneName, storyboardJson, staticAnalysisJson, reviewJson, generatedCode));
    }
}
