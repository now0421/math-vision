package com.mathvision.prompt;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.model.Narrative.Storyboard;

/**
 * Prompts for storyboard validation and codegen-prompt assembly.
 * Used by StoryboardValidationNode (Stage 1c) and CodeGenerationNode (Stage 2).
 * Scene-level design rules live in {@link VisualDesignPrompts}.
 */
public final class NarrativePrompts {

    private static final String COMMON_RULES =
            "You are a STEM narrative designer validating and fixing a structured storyboard for a math teaching visualization.\n"
                    + "The storyboard functions as a visual presentation plan rather than a written solution.\n"
                    + "Introduce foundations before advanced content, and keep the storyboard continuity-safe.\n\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "Layout rules:\n"
                    + "- Frame is 16:9 with " + SystemPrompts.LAYOUT_FRAME_RULES.toLowerCase()
                    .replace("keep important content within", "important content kept inside")
                    .replace("usually keep each step to", "keep simultaneous main visual elements around")
                    .replace(".\n", "\n- ").trim() + "\n"
                    + "- Place formulas near edges, not over the main geometry\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_OBJECT_SEMANTICS
                    .replace("\n- ", "\n- ")
                    .replace("How to interpret the storyboard fields:\n", "Field responsibilities: ").trim() + "\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_SCENE_STRUCTURE.trim() + "\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_SCENE_LAYOUT.trim() + "\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_AUTHORING_RULES
                    + SystemPrompts.GEOMETRIC_MARKER_AUTHORING_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_AUTHORING_RULES
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed relative to the viewport rather than the main geometry\n\n"
                    + "Storyboard-level rules:\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "- Plan per-scene variation: vary the dominant visual focus, spatial layout pattern, and visual density across scenes. Avoid identical composition for consecutive scenes\n"
                    + "- " + SystemPrompts.COLOR_FORMAT_RULES + "\n"
                    + "Storyboard style cleanup rules:\n"
                    + "- Treat the visual design pass as a strong draft, not an immutable contract; this stage may repair layout, style, continuity, and backend practicality before the storyboard becomes the validated downstream authority.\n"
                    + "- Remove or merge redundant storyboard objects introduced by the visual design pass when they do not carry distinct teaching, geometry, dependency, or continuity meaning.\n"
                    + "- Prefer one reusable object plus actions/style changes over multiple near-duplicate labels, highlights, helper objects, or repeated construction elements.\n"
                    + "- Preserve intentional scene-level placement, style, color, and visual hierarchy from the visual design pass unless they cause global consistency, overlap, or readability problems.\n"
                    + "- Once a color is assigned to a concept, it keeps that meaning across the entire storyboard. Record color-to-concept assignments in `global_visual_rules`.\n"
                    + "- Use a single typed `style` object per storyboard object, never a style array and never custom style keys.\n"
                    + "- Style describes the object itself only. Create separate storyboard objects for labels, badges, helper outlines, cards, or callouts that have their own identity.\n"
                    + "- Prefer `kind = text` or `kind = equation` over `kind = text_card` or `kind = formula_card`. Display text directly without a background box/card unless the card itself is teaching-essential (e.g. a titled result panel). Most formulas and labels are clearer without a surrounding box. Convert existing text_card/formula_card objects to text/equation when the card is not teaching-essential.\n"
                    + "- Preserve the motion-first visual-action teaching intent from exploration and visual design: do not turn a movable reveal, construction, transform, or manipulation into a static text/formula-only explanation unless backend practicality makes the motion impossible.\n"
                    + "- When repairing clutter, overlap, or redundancy, simplify supporting text or cards before removing the movable object, action, dependency, or visual evidence that carries the idea.\n"
                    + "- Only include `style` when it adds meaningful rendering properties; omit it for visually plain objects.\n"
                    + SystemPrompts.ASCII_TEXT_RULES;

    private static final String GEOGEBRA_RULES =
            "GeoGebra-specific storyboard validation rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + SystemPrompts.GEOGEBRA_COLOR_RULES
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n"
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_AUTHORING_RULES;

    private static final String MANIM_RULES =
            "Manim-specific storyboard validation rules:\n"
                    + SystemPrompts.MANIM_MOTION_AND_PACING_RULES
                    + SystemPrompts.MANIM_COLOR_RULES
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "- For named teaching-essential points, lines, intersections, and other geometry whose id or value must be read by the learner, preserve or add a visible companion label unless the current beat explicitly hides it.\n"
                    + "- Do not express a visible label with `style.label_visible`; use an explicit `kind: text` or `kind: equation` companion object and attach it with a structured `attachment/label_for` constraint whose refs name the label and parent anchor.\n"
                    + StoryboardSchemaPrompts.MANIM_COMPANION_LABEL_EXAMPLE
                    + "- Prefer dark backgrounds (#1C1C1C to #2D2B55) with light content for maximum contrast and cinema feel when the storyboard does not already establish a different valid style.\n";

    private static final String OUTPUT_FORMAT =
            "Output format:\n"
                    + StoryboardSchemaPrompts.JSON_SYNTAX_REQUIREMENTS
                    + "Return a JSON object with this shape:\n"
                    + StoryboardSchemaPrompts.PATCH_SEMANTICS_NOTE
                    + "{\n"
                    + "  \"continuity_plan\": \"string, how object identities, anchors, and layout stay stable across scenes\",\n"
                    + "  \"global_visual_rules\": [\"string, global staging rule that should hold across the whole presentation\"],\n"
                    + "  \"object_registry\": [\n"
                    + StoryboardSchemaPrompts.OBJECT_DEFINITION_SCHEMA
                    + "\n  ],\n"
                    + "  \"scenes\": [\n"
                    + "    {\n"
                    + StoryboardSchemaPrompts.SCENE_FIELDS_SCHEMA
                    + "\n    }\n"
                    + "  ]\n"
                    + "}\n"
                    + StoryboardSchemaPrompts.TEXT_STYLE_SEMANTICS;

    private static final String EXAMPLE_OUTPUT =
            "Example output:\n"
                    + StoryboardSchemaPrompts.JSON_LEXICAL_EXAMPLES
                    + "{\n"
                    + "  \"continuity_plan\": \"Objects keep stable ids across scenes via object_registry. Anchor-based objects follow their anchors.\",\n"
                    + "  \"global_visual_rules\": [\n"
                    + "    \"Keep major content inside the safe frame.\",\n"
                    + "    \"Prefer transforms and persistent anchors over redraws.\"\n"
                    + "  ],\n"
                    + "  \"object_registry\": [\n"
                    + StoryboardSchemaPrompts.EXAMPLE_NUMBER_LINE
                    + ",\n"
                    + StoryboardSchemaPrompts.EXAMPLE_POINT_P
                    + ",\n"
                    + StoryboardSchemaPrompts.EXAMPLE_FORMULA_CARD
                    + ",\n"
                    + StoryboardSchemaPrompts.EXAMPLE_MIN_MARKER
                    + "\n  ],\n"
                    + "  \"scenes\": [\n"
                    + "    {\n"
                    + StoryboardSchemaPrompts.EXAMPLE_SCENE1_BODY
                    + "\n    },\n"
                    + "    {\n"
                    + StoryboardSchemaPrompts.EXAMPLE_SCENE2_BODY
                    + "\n    }\n"
                    + "  ]\n"
                    + "}\n\n";

    private static final String RESPONSE_RULES =
            OUTPUT_FORMAT
                    + "\n"
                    + EXAMPLE_OUTPUT
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT + " Do not wrap it in markdown.";

    // ========================================================================
    // Placement enrichment prompts (for layout validation of derived objects)
    // ========================================================================

    /** System prompt for the placement-enrichment LLM pass. */
    public static final String PLACEMENT_ENRICHMENT_SYSTEM_PROMPT =
            "You are a coordinate computation assistant for a math visualization storyboard.\n"
                    + "Your only task is to compute placement coordinates for objects that do not have them.\n"
                    + "Rules:\n"
                    + "- Do NOT modify any existing placement that already has data.\n"
                    + "- For objects without placement, compute a reasonable world-space coordinate "
                    + "based on their structured constraints, kind, content, and any available source placements.\n"
                    + "- Use coordinate_space = \"world\" for all computed placements.\n"
                    + "- Write computed placements on the visible scene object patch in entering_objects or persistent_objects where the object appears; do not rely on object_registry-only placements for computed coordinates.\n"
                    + "- If the same object appears in multiple scenes without placement, add the computed placement to each visible scene patch that needs layout validation.\n"
                    + "- Return the complete storyboard JSON with placements added for objects that lacked them. "
                    + "Every other field must remain identical to the input.\n"
                    + "- Frame bounds: x in [-7.11, 7.11], y in [-4, 4]. Keep objects within these bounds.";

    /**
     * Build the user prompt for the placement-enrichment pass.
     *
     * @param storyboardJson serialized storyboard JSON
     * @return user prompt string
     */
    public static String buildPlacementEnrichmentUserPrompt(String storyboardJson) {
        return "Compute placement coordinates for all visible storyboard objects that lack a `placement` field.\n"
                + "Preserve every existing placement exactly as-is; only add new placements where none exists.\n"
                + "Add each computed placement to the object's scene-level patch in `entering_objects` or `persistent_objects`, not only to `object_registry`, because layout validation consumes visible scene patches.\n"
                + "If an object is visible in multiple scenes without placement, add the placement to every such scene patch.\n"
                + "Use the object's structured constraints, kind, content, and any available source placements to infer its position.\n\n"
                + "Storyboard:\n```json\n" + storyboardJson + "\n```\n\n"
                + "Return the full storyboard JSON with the missing visible scene patch placements filled in.";
    }

    // ========================================================================
    // Repair rules (appended to buildRulesPrompt for the LLM cleanup pass)
    // ========================================================================

    /**
     * Build repair-specific rules that are appended to the base rules prompt
     * during the LLM cleanup pass.
     *
     * @param outputTarget the output target backend (manim / geogebra)
     * @return repair rules string
     */
    public static String buildRepairRules(String outputTarget) {
        String defaultBackground = WorkflowConfig.OUTPUT_TARGET_GEOGEBRA.equalsIgnoreCase(outputTarget)
                ? "#FFFFFF" : "#000000";
        return "Storyboard validation repair rules:\n"
                + "Constraint relation catalog for reference:\n"
                + com.mathvision.util.StoryboardConstraintCatalog.detailedCatalogSummary()
                + "\n"
                + "- Use structured constraints as the authoritative dependency graph and semantic contract.\n"
                + "- Use a single typed `style` object only. Do not return style arrays, nested `properties`, role/type layer entries, or custom style keys.\n"
                + "- Rewrite storyboard colors as 6-digit hex strings (`#RRGGBB`) only; do not use named colors or 8-digit hex.\n"
                + "- Keep opacity in separate `opacity`, `fill_opacity`, or `stroke_opacity` fields.\n"
                + "- For text objects, ensure text color contrasts with the text box/background color at ratio >= 4.5, falling back to "
                + defaultBackground + " when no text background is present.\n"
                + "- For non-text objects, ensure foreground colors contrast with " + defaultBackground
                + " at ratio >= 3.0.\n"
                + "- If a derived object is out of bounds, adjust upstream source objects from structured constraint refs, the whole constrained group, or the camera/layout; do not repair by directly moving that derived object when it would contradict structured constraints.\n"
                + "- Every dependency-driven object must define structured constraints for hard geometric invariants, with refs naming owner objects and source objects using catalog role names.\n"
                + "- ASCII repair is mandatory: rewrite every JSON string value so it contains only characters with code <= 0x7F.\n"
                + "- Apply this normalization map wherever needed: U+2018 and U+2019 -> `'`; U+201C and U+201D -> `\"`; U+2013 and U+2014 -> `-`; U+2212 -> `-`; U+00D7 -> `x`; U+2260 -> `!=`; U+2264 -> `<=`; U+2265 -> `>=`.\n"
                + "- Repair examples: `hiker` + U+2019 + `s` becomes `hiker's`; `PB'` + U+2014 + `a` becomes `PB' - a`; `right` + U+2014 + `the` becomes `right - the`; `P_test ` + U+2260 + ` P_min` becomes `P_test != P_min`.\n"
                + "Angle boundary-vertex consistency rules:\n"
                + "- For every angle_between, right_angle_at, or angle-like arc_sweep constraint, each boundary line (line_a, line_b, start_boundary, end_boundary, ray_a, ray_b) must pass through the declared vertex/anchor.\n"
                + "- A boundary line passes through the vertex when a structured connector constraint names the vertex as one endpoint (e.g. `connects_points` refs include both endpoints; `line_through_points` or `ray_from_to` refs include the vertex and another point).\n"
                + "- If no structured constraint shows that a boundary passes through the vertex, the line likely does not pass through the vertex, and the angle will be drawn at the wrong location.\n"
                + "- Common mistake: using a perpendicular or normal from a different point as the angle boundary. For example, using a perpendicular from A to l as the normal at P_min is wrong if P_min is not on that perpendicular. Instead, create a normal at P_min (a line through P_min perpendicular to l).\n"
                + "- When fixing, either: (a) replace the incorrect boundary reference with a line that passes through the vertex, or (b) create a new helper object (e.g. a normal at the vertex) and reference it instead.\n"
                + "- Verify that equal-angle markers reference symmetrically constructed boundaries so downstream code can render them correctly.";
    }

    /**
     * Build the user prompt for the LLM cleanup/repair pass.
     *
     * @param storyboardJson serialized storyboard JSON
     * @param issues         list of static validation issues (may be null or empty)
     * @return user prompt string
     */
    public static String buildCleanupUserPrompt(String storyboardJson, java.util.List<String> issues) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Please clean up this storyboard so it is coherent, and ensure that all coordinate-based elements stay within bounds and do not visibly overlap.\n");
        userPrompt.append("Preserve the original narrative order, object identity, and motion-first visual teaching intent as much as possible; only adjust the layout and wording where necessary.\n");
        userPrompt.append("Replace every non-ASCII text token reported below with an ASCII equivalent across the full storyboard.\n");
        userPrompt.append("For each reported token, locate every occurrence in the current storyboard and rewrite the surrounding sentence if needed so the whole string is ASCII-only. For example, replace curly apostrophes with straight apostrophes, em/en dashes with ` - ` or `-`, and mathematical comparison glyphs with ASCII operators such as `!=`, `<=`, or `>=`.\n");
        userPrompt.append("Before returning, perform a final character-by-character pass over every JSON string value and ensure no character code is greater than 0x7F.\n");
        userPrompt.append("If you find issues, fix them directly. If there are no issues, still perform a full cleanup to make the storyboard more stable and readable.\n");
        if (issues != null && !issues.isEmpty()) {
            userPrompt.append("Static validation findings:\n");
            for (String issue : issues) {
                if (issue.startsWith("Issue:")) {
                    userPrompt.append(issue).append("\n");
                } else {
                    userPrompt.append("- ").append(issue).append("\n");
                }
            }
            userPrompt.append("\n");
        }
        userPrompt.append("Current storyboard:\n```json\n").append(storyboardJson).append("\n```\n\n");
        userPrompt.append("Return the full corrected storyboard JSON with all scenes, object_registry, and metadata.");
        return userPrompt.toString();
    }

    private NarrativePrompts() {}

    public static String buildRulesPrompt(String outputTarget) {
        String prompt = COMMON_RULES;
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            prompt += SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA_REPAIR;
            prompt += "\n" + GEOGEBRA_RULES;
        } else if ("manim".equalsIgnoreCase(outputTarget)) {
            prompt += SystemPrompts.STORYBOARD_FIELD_GUIDE_MANIM_REPAIR;
            prompt += "\n" + MANIM_RULES;
        }
        prompt += "\n" + RESPONSE_RULES;
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(SystemPrompts.ensureGeoGebraStyleReference(prompt));
        }
        if ("manim".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(SystemPrompts.ensureManimStyleReference(prompt));
        }
        return SystemPrompts.buildRulesSection(prompt);
    }

    public static String buildFixedContextPrompt(String targetConcept,
                                                 String targetDescription,
                                                 String outputTarget) {
        return buildFixedContextPrompt(targetConcept, targetDescription, outputTarget, "");
    }

    public static String buildFixedContextPrompt(String targetConcept,
                                                 String targetDescription,
                                                 String outputTarget,
                                                 String solutionChainSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append(SystemPrompts.buildWorkflowPrefix(
                "Stage 1c / Storyboard Validation",
                "Storyboard composition and validation",
                targetConcept,
                targetDescription,
                outputTarget
        ));
        sb.append("Output target backend: ").append(outputTarget).append(".\n");
        sb.append("Keep the storyboard reusable, but make it practical for this backend.\n");
        if (solutionChainSummary != null && !solutionChainSummary.isBlank()) {
            sb.append("\n").append(solutionChainSummary.trim()).append("\n");
        }
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    public static String storyboardCodegenPrompt(Storyboard storyboard,
                                                 String outputTarget) {
        return storyboardCodegenPrompt(
                StoryboardJsonBuilder.buildForCodegen(storyboard),
                outputTarget);
    }

    public static String storyboardCodegenPrompt(String storyboardJson,
                                                 String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildCurrentRequestSection(String.format(
                    "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                            + "Remember: Return ONLY the single GeoGebra code block. No explanation.",
                    storyboardJson));
        }
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                storyboardJson));
    }
}
