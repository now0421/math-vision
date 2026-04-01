package com.automanim.prompt;

/**
 * Prompts for Stage 1b: visual design.
 */
public final class VisualDesignPrompts {

    private static final String SYSTEM =
            "You are a visual designer for math teaching visualizations.\n"
                    + "Turn abstract reasoning into something the viewer can see, compare, track, or interact with.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + "Visual design principles:\n"
                    + "- Prefer direct visual reasoning over text-heavy explanation.\n"
                    + "- Keep the viewer oriented around one stable diagram when possible.\n"
                    + "- Let formulas support the visual argument instead of replacing it.\n"
                    + "- If a reasoning step is not naturally visible, design a faithful visual proxy.\n"
                    + "- Keep the design reusable across different presentation backends such as animated scenes or interactive geometry.\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES.replace("\n", "\n- ").trim() + "\n\n"
                    + "Visual-planning constraints:\n"
                    + "- " + SystemPrompts.LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed.\n"
                    + "- Keep the visual plan implementable without hidden assumptions.\n\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"layout\": \"string, the main visible composition and the relative spatial placement of major elements\",\n"
                    + "  \"motion_plan\": \"string, how the visual state changes over time, steps, or interaction\",\n"
                    + "  \"scene_mode\": \"string, 2d by default or 3d only when depth is genuinely needed\",\n"
                    + "  \"camera_plan\": \"string, viewpoint, framing, or attention-guidance plan\",\n"
                    + "  \"screen_overlay_plan\": \"string, text, formulas, counters, labels, or UI-style annotations that sit outside the main geometry layout\",\n"
                    + "  \"color_scheme\": \"string, semantic color roles and emphasis plan; keep foreground/background pairings high-contrast\",\n"
                    + "  \"duration\": \"number, approximate duration in seconds when timing matters\",\n"
                    + "  \"color_palette\": [\"string, concrete color name when useful\"]\n"
                    + "}\n\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private VisualDesignPrompts() {}

    public static String systemPrompt(String targetConcept,
                                      String targetDescription,
                                      String outputTarget) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 1b / Visual Design",
                "Scene visual design",
                targetConcept,
                targetDescription,
                true
        ) + "Output target backend: " + outputTarget + ".\n"
                + "Design for this backend while keeping the visual intent as reusable and backend-neutral as possible.\n\n"
                + SYSTEM;
    }
}
