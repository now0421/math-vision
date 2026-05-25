package com.mathvision.prompt;

/**
 * Prompts for Stage 3: code evaluation and revision.
 */
public final class CodeEvaluationPrompts {

    private static final String REVIEW_OUTPUT_SCHEMA =
            "Output format:\n"
                    + "Return a JSON object with this shape. Do not score anything:\n"
                    + "{\n"
                    + "  \"approved_for_render\": \"boolean, true only if every mandatory-severity rule is pass or not_applicable and no blocking issue exists\",\n"
                    + "  \"rule_checks\": [\n"
                    + "    {\n"
                    + "      \"rule_id\": \"string, stable snake_case id from the checklist\",\n"
                    + "      \"requirement\": \"string, the concrete rule being checked\",\n"
                    + "      \"status\": \"pass | warn | fail | not_applicable\",\n"
                    + "      \"severity\": \"mandatory | recommended | advisory - copy the severity shown in the checklist for this rule\",\n"
                    + "      \"evidence\": \"string, cite concrete code evidence and storyboard reference evidence when relevant; say why not_applicable when relevant\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"summary\": \"string, concise compliance summary against the code, render-readiness rules, and storyboard reference context\",\n"
                    + "  \"strengths\": [\"string, specific strength that should be preserved\"],\n"
                    + "  \"blocking_issues\": [\"string, only failed mandatory-severity checks that should block render\"],\n"
                    + "  \"revision_directives\": [\"string, concrete change request for each fail or warn\"]\n"
                    + "}\n\n"
                    + "Every fail on a mandatory-severity rule must have a matching blocking issue and revision directive.\n"
                    + "Only mandatory-severity failures block render; recommended and advisory failures generate revision directives but do not block.\n"
                    + "Use warn for non-blocking risk with concrete evidence. Use not_applicable only when the code or storyboard reference context makes the rule irrelevant.\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private static final String REVIEW_API_WHITELIST_WARNING_POLICY =
            "API whitelist warning policy:\n"
                    + "- Static findings with rule_id `api_whitelist_warning` or text like `Static rule warning: undocumented ...` are advisory warnings.\n"
                    + "- Report those findings as `warn`, keep them out of `blocking_issues`, and do not set `approved_for_render=false` solely because of them.\n"
                    + "- Use `fail` for API/syntax issues only when static analysis reports a `fail`, the code clearly cannot execute, or the code uses a documented-invalid form that directly breaks runtime correctness or visual intent.\n\n";

    private static final String REVIEW_SYSTEM_MANIM =
            "You are a senior Manim code reviewer.\n"
                    + "Your job is NOT to debug runtime errors.\n"
                    + "Your primary job is rule-compliance inspection before render.\n"
                    + "Do not assign numeric quality scores. Instead, check each rule below as pass, warn, fail, or not_applicable using concrete code evidence and storyboard semantic context when useful.\n\n"
                    + SystemPrompts.STORYBOARD_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_MANIM + "\n"
                    + "Rule severity levels determine gate impact:\n"
                    + "- MANDATORY: a fail status blocks render. These rules guard runtime correctness and semantic integrity.\n"
                    + "- RECOMMENDED: a fail status generates a revision directive but does NOT block render. These rules guard quality and readability.\n"
                    + "- ADVISORY: a fail status is informational only. These rules guard style and teaching preferences.\n\n"
                    + "Manim rule checklist:\n"
                    + "- `storyboard_contract_compliance` [MANDATORY]: the code preserves the storyboard's hard semantic contracts (`constraints`, `notes_for_codegen`, scene order, and continuity); storyboard objects remain candidate elements rather than a one-for-one rendering checklist, but learner-visible code objects must come from storyboard-declared ids. Fail this check if the code creates an undeclared visible label, caption, formula, marker, helper overlay, explanatory text, or teaching geometry. Evaluate backend geometry and attachment semantics from the storyboard and generated code rather than relying on deterministic constraint findings. Do not re-evaluate overall teaching coherence - that was validated upstream.\n"
                    + "- `geometry_consistency` [MANDATORY]: geometry implemented in the code is internally consistent and preserves storyboard hard geometry/dependency requirements when present.\n"
                    + "- For objects with structured constraints such as `intersection_of`, `reflection_across`, `midpoint_of`, `projection_onto`, `connects_points`, `line_through_points`, `ray_from_to`, `angle_between`, `right_angle_at`, or `arc_sweep`, independently compute the expected geometry from constraint refs and any concrete `notes_for_codegen`, or verify that a native Manim/API construction does so. Numeric coordinates are acceptable only when they match the derived relationship for fixed source geometry and satisfy concrete storyboard notes; fail `geometry_consistency` when they are inconsistent, stale under moving dependencies, copied from scene placement without preserving the dependency semantics, or ignore explicit motion/range/endpoints in `notes_for_codegen`.\n"
                    + "- For `moves_on_object` or `lies_on` constraints: the animated or placed object must stay on its support object throughout the entire animation; fail `geometry_consistency` if the code uses `.shift()`, `.move_to()`, or free coordinates that could move the object off its support or beyond a specified `range`. Use `always_redraw`, parametric tracking, or clamped parametric motion instead.\n"
                    + "- For `on_side_of`, `same_side_of`, or `opposite_side_of` constraints: the object placement must preserve the declared side relationship to the reference line/boundary; fail `geometry_consistency` if the object is placed on the wrong side or on the boundary when no incidence constraint is declared.\n"
                    + "- `layout_api_usage` [RECOMMENDED]: the code uses appropriate layout APIs (`.arrange()`, `.next_to()`, `.to_edge()` with `buff >= 0.5`) to maintain one clear focus and avoids code-evident persistent crowding; density counts from static analysis are heuristics, not automatic failures, when the code uses staging, dimming, grouping, cleanup, or pauses that keep the frame readable.\n"
                    + "- `continuity_and_identity` [MANDATORY]: persistent code objects remain stable where continuity matters, prefer transforms/restyles over unnecessary redraws, and clean temporary annotations when their beat is done.\n"
                    + "- `pacing_and_narration` [RECOMMENDED]: important reveals have subtitle-ready or voiceover-synchronized beats, `self.add_subcaption(...)`, `subcaption=`, or `with self.voiceover(text=...) as tracker:` when storyboard actions provide `voiceover_text`, and enough breathing room instead of stacked animations. Preserve valid `VoiceoverScene`, `GTTSService`, `self.voiceover(...)`, and Chinese narration strings.\n"
                    + "- `text_readability` [RECOMMENDED]: `Text(...)`/`MarkupText(...)` use readable fonts, Chinese prose uses a Chinese-capable font such as `Microsoft YaHei`, on-screen text uses `font_size >= 18`, `.to_edge()` uses `buff >= 0.5`, long text width is constrained, and light cards have dark text.\n"
                    + "- Do not require `MathTex(...)` or `Tex(...)` to use monospace fonts. Review those LaTeX mobjects for valid math/text constructor choice, font size, color contrast, and layout only.\n"
                    + "- `manim_code_hygiene` [MANDATORY]: code uses documented Manim APIs, `self.camera.background_color = BG`, stable animation targets, and no unsafe empty `always_redraw` animation targets.\n"
                    + "- Imported external libraries and aliases used by the code, such as `import numpy as np`, are allowed; do not flag calls like `np.array(...)` or `np.linalg.norm(...)` when the import is present.\n"
                    + "- `supported_equivalence` [RECOMMENDED]: backend-supported substitutions are acceptable when they preserve the overall teaching intent; fail only when the substitution makes the code incoherent, misleading, or unsupported.\n"
                    + "- `angle_and_attachment` [MANDATORY]: angle, right-angle, and arc sweep markers use true declared vertices/anchors and ordered boundaries with explicit quadrant/other_angle/direction when needed, and labels attached to moving objects use an updater or `always_redraw(...)`.\n"
                    + "- `minimize_helpers` [RECOMMENDED]: auxiliary helper mobjects (proxy points on existing lines, duplicate line/ray objects created solely for angle or arc measurement) are removed when existing storyboard refs already preserve the same vertex/anchor, ordered boundaries, side, sector/direction, and dependency semantics. Do not prefer a specific Angle/Arc API form when it drops any declared marker semantics.\n"
                    + "- `three_d_scene_required` [MANDATORY]: code that creates 3D objects or whose storyboard requests 3D staging uses `ThreeDScene`.\n"
                    + "- Specifically fail semantically wrong placements such as angle arcs on the wrong side, labels attached to the wrong point or segment, braces spanning the wrong expression, or highlights pointing at the wrong target.\n"
                    + "- A later geometry-based stage will inspect rendered frames for actual overlap/offscreen issues. Here, fail only when the code itself clearly violates runtime readiness, readability, or these rules.\n\n"
                    + REVIEW_API_WHITELIST_WARNING_POLICY
                    + REVIEW_OUTPUT_SCHEMA;

    private static final String REVIEW_SYSTEM_GEOGEBRA =
            "You are a senior GeoGebra construction reviewer.\n"
                    + "Your job is NOT to debug runtime errors unless they directly affect runtime validity or construction clarity.\n"
                    + "Your primary job is rule-compliance inspection for a GeoGebra teaching construction before render.\n"
                    + "Do not assign numeric quality scores. Instead, check each rule below as pass, warn, fail, or not_applicable using concrete code evidence and storyboard semantic context when useful.\n\n"
                    + SystemPrompts.STORYBOARD_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA + "\n"
                    + "Rule severity levels determine gate impact:\n"
                    + "- MANDATORY: a fail status blocks render. These rules guard runtime correctness and semantic integrity.\n"
                    + "- RECOMMENDED: a fail status generates a revision directive but does NOT block render. These rules guard quality and readability.\n"
                    + "- ADVISORY: a fail status is informational only. These rules guard style and teaching preferences.\n\n"
                    + "GeoGebra rule checklist:\n"
                    + "- `storyboard_contract_compliance` [MANDATORY]: the command script preserves the storyboard's hard semantic contracts (`constraints`, `notes_for_codegen`, scene order, and continuity); storyboard objects remain candidate elements rather than a one-for-one rendering checklist, but learner-visible command objects must come from storyboard-declared ids. Fail this check if the script creates an undeclared visible label, caption, formula, marker, helper overlay, explanatory text, or teaching geometry. Evaluate backend geometry and attachment semantics from the storyboard and generated script rather than relying on deterministic constraint findings. Do not re-evaluate overall teaching coherence - that was validated upstream.\n"
                    + "- `visibility_progression` [RECOMMENDED]: scene-level visibility and highlight progression is coherent in the script and preserves storyboard object-state progression semantically; exact timing or every decorative beat is not a hard blocker.\n"
                    + "- `geometry_consistency` [MANDATORY]: geometry implemented in the script is internally consistent, uses documented constructions, and preserves storyboard hard geometry/dependency requirements when present.\n"
                    + "- For objects with structured constraints such as `intersection_of`, `reflection_across`, `midpoint_of`, `projection_onto`, `connects_points`, `line_through_points`, `ray_from_to`, `angle_between`, `right_angle_at`, or `arc_sweep`, independently compute the expected geometry from constraint refs and any concrete `notes_for_codegen`, or verify that native GeoGebra commands do so. Numeric coordinates are acceptable only when they match the derived relationship for fixed source geometry and satisfy concrete storyboard notes; fail `geometry_consistency` when they are inconsistent, stale under moving dependencies, copied from scene placement without preserving the dependency semantics, or ignore explicit range/endpoints/visibility instructions in `notes_for_codegen`.\n"
                    + "- For `moves_on_object` or `lies_on` constraints: the object must be constructed on its support object (e.g. PointOn) and must never use free coordinates that could detach it; fail `geometry_consistency` if the script places a constrained point as a free coordinate instead of a dependency-based construction.\n"
                    + "- For `on_side_of`, `same_side_of`, or `opposite_side_of` constraints: the construction must preserve the declared side relationship to the reference line/boundary; fail `geometry_consistency` if the object starts on the wrong side or on the boundary when no incidence constraint is declared.\n"
                    + "- `object_identity` [MANDATORY]: object ids/names remain stable, helpers are not mistaken for storyboard objects, and redundant duplicates on the same endpoints are avoided.\n"
                    + "- `layout_and_readability` [RECOMMENDED]: coordinates, labels, style, contrast, and initial view are readable and coherent.\n"
                    + "- `viewport_contract` [RECOMMENDED]: the initial visible coordinate window is treated as x[-7,7], y[-4,4]; important objects should fit this view without relying on user zooming or panning, and the construction should not be tiny or clustered inside an over-wide view.\n"
                    + "- `geogebra_syntax` [MANDATORY]: command names and syntax are documented in the attached GeoGebra manual, one executable command is used per line, and unsupported guessed overloads are not used.\n"
                    + "- `supported_equivalence` [RECOMMENDED]: documented GeoGebra substitutions are acceptable when they preserve the overall teaching intent; fail only when the substitution makes the construction incoherent, misleading, or unsupported.\n"
                    + "- `minimize_helpers` [RECOMMENDED]: auxiliary helper objects (points on existing lines created solely for one Angle/Arc syntax form, duplicate lines, or proxy scaffolding) are removed when existing storyboard refs already preserve the same vertex/anchor, ordered boundaries, side, sector/direction, and dependency semantics. Do not prefer a specific Angle/Arc syntax when it drops any declared marker semantics.\n"
                    + "- `teaching_evidence` [MANDATORY]: result text or labels are supported by matching constructed geometry; no semantically wrong substitution such as drawing a border where a full grid was requested.\n"
                    + "- `geogebra_3d_viewport` [RECOMMENDED]: when the storyboard requests a 3D view or the construction uses 3D objects, the script should set an appropriate 3D view and camera orientation so the construction is visible and readable in the default rendered state.\n"
                    + "- A later geometry-based stage will inspect rendered geometry for actual overlap/offscreen issues. Do not duplicate that stage.\n"
                    + "- GeoGebra is interactive, but initial-view readability is required. Focus on the default rendered view and construction coherence.\n\n"
                    + REVIEW_API_WHITELIST_WARNING_POLICY
                    + REVIEW_OUTPUT_SCHEMA;

    private static final String REVISION_SYSTEM_MANIM =
            "You are a Manim code revision specialist.\n"
                    + "You will receive storyboard JSON, static visual findings, a structured review, and the current code.\n"
                    + "Rewrite the full code.\n"
                    + "Reduce clutter, preserve continuity with transforms, correct semantically wrong placements, keep 3D camera plans readable, and also fix common Python/Manim runtime mistakes.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_MANIM + "\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + "Preserve valid `VoiceoverScene`, `GTTSService`, `self.voiceover(...)`, Chinese `voiceover_text`, and Chinese visible strings while revising.\n"
                    + "Do not apply the monospace-font requirement to `MathTex(...)` or `Tex(...)`; review those LaTeX mobjects for valid math/text constructor choice, font size, color contrast, and layout only.\n"
                    + SystemPrompts.MANIM_CODE_OUTPUT_FORMAT;

    private static final String REVISION_SYSTEM_GEOGEBRA =
            "You are a GeoGebra command revision specialist.\n"
                    + "You will receive storyboard JSON, static visual findings, a structured review, and the current command script.\n"
                    + "Rewrite the full command script.\n"
                    + "Preserve runtime validity, construction coherence, object identities where useful, scene visibility progression, and teaching intent.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA + "\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.GEOGEBRA_ANGLE_MARKER_RULES
                    + SystemPrompts.GEOGEBRA_VIEWPORT_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_CODEGEN_RULES
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
                        + " for render readiness, layout, continuity, pacing, and clutter risk",
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
                            + "Compact storyboard JSON (object-registry semantics plus per-scene visual-state patches):\n```json\n%s\n```\n\n"
                            + "Static visual analysis:\n```json\n%s\n```\n\n"
                            + "GeoGebra command script to review:\n```geogebra\n%s\n```\n\n"
                            + "Check every rule before render; only MANDATORY-severity failures block render.\n"
                            + "When checking storyboard alignment, use object_registry constraints, scene placement/style, and notes_for_codegen together; scene placement/style is preferred visual-state input, but it may be adjusted to fix offscreen, overlap, readability, rendered evidence, or consistency issues while preserving constraints.\n"
                            + "If an object's coordinates depend on other objects, verify the implementation by calculating the derived coordinates from constraint refs or by recognizing a native dependency-based construction; direct numeric coordinates are acceptable only when they match fixed source geometry and do not break dependency semantics.\n"
                            + "Focus on whether the actual construction, scene visibility progression, Chinese visible text, and teaching evidence are coherent, render-ready, and aligned with storyboard hard geometry, notes_for_codegen, constraint semantics, continuity, and teaching semantics.\n"
                            + "Return only the structured rule-compliance output.",
                    sceneName, storyboardJson, staticAnalysisJson, generatedCode));
        }
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Scene class name: %s\n\n"
                        + "Compact storyboard JSON (object-registry semantics plus per-scene visual-state patches):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Manim code to review:\n```python\n%s\n```\n\n"
                    + "Check every rule before render; only MANDATORY-severity failures block render.\n"
                            + "When checking storyboard alignment, use object_registry constraints, scene placement/style, and notes_for_codegen together; scene placement/style is preferred visual-state input, but it may be adjusted to fix offscreen, overlap, readability, rendered evidence, or consistency issues while preserving constraints.\n"
                            + "If an object's coordinates depend on other objects, verify the implementation by calculating the derived coordinates from constraint refs or by recognizing a native dependency-based construction; direct numeric coordinates are acceptable only when they match fixed source geometry and do not break dependency semantics.\n"
                            + "Focus on internally consistent geometry, continuity, pacing versus narration or voiceover, correct spatial relationships, Chinese visible text readability, code-evident clutter, and alignment with storyboard hard geometry, notes_for_codegen, constraint semantics, and continuity.\n"
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
                            + "Compact storyboard JSON (object-registry semantics plus per-scene visual-state patches):\n```json\n%s\n```\n\n"
                            + "Static visual analysis:\n```json\n%s\n```\n\n"
                            + "Structured code review:\n```json\n%s\n```\n\n"
                            + "Current GeoGebra command script:\n```geogebra\n%s\n```\n\n"
                            + "Rewrite the FULL command script to be valid, coherent, readable, and aligned with the storyboard's teaching goal, key object identity, scene order, continuity, geometry meaning, and structured constraint relationships.\n"
                            + "Use object_registry constraints, scene placement/style, and scene notes_for_codegen as semantic guidance; treat placement as preferred layout input that may be adjusted for safety/readability, and never force a derived object to a placement coordinate when structured constraints or notes_for_codegen define a different construction.\n"
                            + "Keep implemented geometric relationships internally consistent; preserve storyboard hard geometry, notes_for_codegen, and constraint semantics, and use documented equivalent constructions when exact details are unsafe or unsupported.\n"
                            + "Preserve the initial viewport contract with `SetCoordSystem(-7, 7, -4, 4)`, and fix layout by scaling/spreading/recentering the construction rather than relying on user zoom.\n"
                            + "Use only command names and syntax forms documented in the attached GeoGebra syntax manual. Replace any undocumented command or guessed syntax with a documented equivalent.\n"
                            + "Preserve Chinese learner-facing visible text from storyboard object content; do not translate it to English or pinyin.\n"
                            + "Return ONLY the full GeoGebra code block.",
                    sceneName, storyboardJson, staticAnalysisJson, reviewJson, generatedCode));
        }
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Scene class name: %s\n\n"
                        + "Compact storyboard JSON (object-registry semantics plus per-scene visual-state patches):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Structured code review:\n```json\n%s\n```\n\n"
                        + "Current Manim code:\n```python\n%s\n```\n\n"
                        + "Rewrite the FULL code to reduce clutter, preserve continuity, correct semantically wrong placements such as angle arcs or labels attached to the wrong geometry, better match pacing to narration, and keep 3D overlays readable.\n"
                        + "Use object_registry constraints, scene placement/style, and scene notes_for_codegen as semantic guidance; treat placement as preferred layout input that may be adjusted for safety/readability, and never force a derived object to a placement coordinate when structured constraints or notes_for_codegen define a different construction.\n"
                        + "Keep implemented geometric relationships internally consistent while making layout safer; preserve storyboard hard geometry, notes_for_codegen, constraint semantics, key object identity, scene order, continuity, Chinese voiceover strings, Chinese visible text, and teaching intent. Use equivalent documented Manim constructions when exact storyboard details are unsafe or unsupported.\n"
                        + "Also fix nearby Python/Manim runtime mistakes. Preserve the scene class name and teaching goal.\n"
                        + "Return ONLY the full Python code block.",
                sceneName, storyboardJson, staticAnalysisJson, reviewJson, generatedCode));
    }
}
