package com.mathvision.prompt;

/**
 * Prompts for Stage 1b: visual design.
 */
public final class VisualDesignPrompts {

    private static final String OUTPUT_FORMAT =
            "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"layout\": \"string, the main visible composition and ONLY the relative spatial placement of major elements; describe left/right/above/below/center/adjacent/grouped relationships only, never concrete coordinates or numeric positions\",\n"
                    + "  \"motion_plan\": \"string, how the visual state changes over time, steps, or interaction\",\n"
                    + "  \"scene_mode\": \"string, 2d by default or 3d only when depth is genuinely needed\",\n"
                    + "  \"camera_plan\": \"string, viewpoint, framing, or attention-guidance plan\",\n"
                    + "  \"screen_overlay_plan\": \"string, text, formulas, counters, labels, or UI-style annotations that sit outside the main geometry layout\",\n"
                    + "  \"color_scheme\": \"string, semantic color roles and emphasis plan; follow the active backend's style rules\",\n"
                    + "  \"duration\": \"number, approximate duration in seconds when timing matters\",\n"
                    + "  \"color_palette\": [\"string, concrete color name when useful\"]\n"
                    + "}\n\n"
                    + "For `layout`, describe only relative placement and composition. Do not output coordinates, axis values, or exact numeric positions.\n\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private static final String MANIM_SYSTEM =
            "You are a Manim-first visual designer for math teaching visualizations.\n"
                    + "Turn abstract reasoning into a learner-facing visual plan before any code is written.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + "Manim visual-planning constraints:\n"
                    + "- " + SystemPrompts.MANIM_LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed for the teaching goal.\n"
                    + "- Plan where the eye should look first, what remains as dim context, and what area stays open for overlays or later reveals.\n"
                    + "- Prefer dark backgrounds (#1C1C1C to #2D2B55) with light content for maximum contrast and cinema feel.\n"
                    + "- Assign colors to concepts, not to individual objects. Once a color is assigned to a concept, it keeps that meaning across the entire presentation.\n"
                    + "- Plan per-scene variation: vary the dominant color, spatial layout, animation entry style, and visual density across scenes. Never use identical visual config for every scene.\n"
                    + "- Prefer a stable world layout and meaningful transforms over repeatedly replacing the whole diagram.\n"
                    + "- Distinguish what should animate from what should stay static; motion is not mandatory.\n"
                    + "- The plan must be implementable with documented Manim constructs and no hidden assumptions.\n\n"
                    + OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are a visual designer for GeoGebra teaching constructions.\n"
                    + "Turn abstract reasoning into something the learner can see, compare, or manipulate.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + "Visual design principles:\n"
                    + "- Prefer direct visual reasoning over text-heavy explanation.\n"
                    + "- Keep the learner oriented around one stable construction when possible.\n"
                    + "- Let formulas support the visual argument instead of replacing it.\n"
                    + "- If a reasoning step is not naturally visible, design a faithful construction-based proxy.\n"
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS + "\n"
                    + "GeoGebra planning constraints:\n"
                    + "- " + SystemPrompts.LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed.\n"
                    + "- Keep the visual plan implementable without hidden assumptions.\n"
                    + "- Prefer readable construction layout and clear label placement over animation-like staging language.\n\n"
                    + OUTPUT_FORMAT;

    private VisualDesignPrompts() {}

    public static String systemPrompt(String targetConcept,
                                      String targetDescription,
                                      String outputTarget) {
        String prompt = SystemPrompts.buildWorkflowPrefix(
                "Stage 1b / Visual Design",
                "Scene visual design",
                targetConcept,
                targetDescription,
                outputTarget
        ) + "Output target backend: " + outputTarget + ".\n"
                + ("geogebra".equalsIgnoreCase(outputTarget)
                ? "Design for GeoGebra as an interactive construction medium.\n\n" + GEOGEBRA_SYSTEM
                : "Design for Manim as a teaching animation medium rather than a backend-neutral compromise.\n\n"
                + MANIM_SYSTEM);
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureGeoGebraStyleReference(prompt);
        }
        return SystemPrompts.ensureManimStyleReference(prompt);
    }
}
