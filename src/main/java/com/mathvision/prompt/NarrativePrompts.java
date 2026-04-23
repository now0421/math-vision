package com.mathvision.prompt;

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
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_CORE
                    .replace("\n- ", "\n- ")
                    .replace("How to interpret the storyboard fields:\n", "Field responsibilities: ").trim() + "\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_EXTENDED.trim() + "\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_AUTHORING_RULES
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed relative to the viewport rather than the main geometry\n\n"
                    + "Storyboard-level rules:\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "- Plan per-scene variation: vary the dominant visual focus, spatial layout pattern, and visual density across scenes. Avoid identical composition for consecutive scenes\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES + "\n";

    private static final String GEOGEBRA_RULES =
            "GeoGebra-specific storyboard validation rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n";

    private static final String MANIM_RULES =
            "Manim-specific storyboard validation rules:\n"
                    + SystemPrompts.MANIM_MOTION_AND_PACING_RULES
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "- Once a color is assigned to a concept, it keeps that meaning across the entire storyboard. Record color-to-concept assignments in `global_visual_rules`.\n";

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

    private static final String SYSTEM =
            COMMON_RULES
                    + OUTPUT_FORMAT
                    + "\n"
                    + EXAMPLE_OUTPUT
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT + " Do not wrap it in markdown.";

    private NarrativePrompts() {}

    public static String systemPrompt(String targetConcept,
                                      String targetDescription,
                                      String outputTarget) {
        String prompt = SystemPrompts.buildWorkflowPrefix(
                "Stage 1c / Storyboard Validation",
                "Storyboard composition and validation",
                targetConcept,
                targetDescription,
                outputTarget
        ) + "Output target backend: " + outputTarget + ".\n"
                + "Keep the storyboard reusable, but make it practical for this backend.\n";

        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            prompt += "\n" + GEOGEBRA_RULES;
        } else if ("manim".equalsIgnoreCase(outputTarget)) {
            prompt += "\n" + MANIM_RULES;
        }

        prompt += "\n" + SYSTEM;

        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureGeoGebraStyleReference(prompt);
        }
        if ("manim".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureManimStyleReference(prompt);
        }
        return prompt;
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
            return String.format(
                    "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                            + "Remember: Return ONLY the single GeoGebra code block. No explanation.",
                    storyboardJson);
        }
        return String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                storyboardJson);
    }
}
