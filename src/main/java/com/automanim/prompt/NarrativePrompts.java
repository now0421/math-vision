package com.automanim.prompt;

import com.automanim.model.Narrative.Storyboard;

/**
 * Prompts for Stage 1c: narrative composition.
 */
public final class NarrativePrompts {

    private static final String SYSTEM =
            "You are a STEM narrative designer writing a structured storyboard for a Manim animation.\n"
                    + "Write a scene-by-scene storyboard that functions as an animation script rather than a written solution.\n"
                    + "Begin with a clear hook, introduce foundations before advanced content, and keep the storyboard continuity-safe.\n"
                    + "\n"
                    + "For every scene, explicitly provide:\n"
                    + "- goal, concise narration, and ordered actions\n"
                    + "- entering, persistent, and exiting object ids\n"
                    + "- explicit spatial placement through `layout_goal` and `entering_objects.placement`\n"
                    + "- a `safe_area_plan`\n"
                    + "\n"
                    + "Layout rules:\n"
                    + "- Frame is 16:9 with important content kept inside x[-7,7], y[-4,4]\n"
                    + "- Leave about 1 unit margin from edges\n"
                    + "- Keep simultaneous main visual elements around 6 to 8\n"
                    + "- Place formulas near edges, not over the main geometry\n"
                    + "- Keep the diagram stable across scenes and change only the necessary layer\n"
                    + "\n"
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed in frame\n"
                    + "\n"
                    + "Narrative rules:\n"
                    + "- Narrative must not be constrained by a fixed word count\n"
                    + "- Use enrichment fields only when they sharpen the explanation\n"
                    + "- If the target is a problem, every scene must directly advance the solution\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "\n"
                    + "Return only valid JSON. Do not wrap it in markdown.";

    private NarrativePrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 1c / Narrative Composition",
                "Storyboard composition",
                targetConcept,
                targetDescription,
                true
        ) + SYSTEM;
    }

    public static String conceptUserPrompt(String targetConcept, String stepContext) {
        return String.format(
                "Target concept: %s\n\nStep progression chain:\n%s\n\nProduce a continuity-safe storyboard JSON, not free text.",
                targetConcept, stepContext);
    }

    public static String problemUserPrompt(String problemStatement,
                                           String solvingContext,
                                           int targetSceneCount) {
        return String.format(
                "Math problem to solve: %s\n\nOrdered solution-step graph context:\n%s\n\n"
                        + "Write the animation as a structured problem-solving storyboard, not as a plain written solution.\n"
                        + "Start by establishing the problem situation, then move through the key solving beats in order.\n"
                        + "Target about %d scenes total, but merge nodes whenever that improves focus and continuity.\n"
                        + "Return storyboard JSON only.",
                problemStatement, solvingContext, targetSceneCount);
    }

    public static String storyboardCodegenPrompt(String targetConcept, Storyboard storyboard) {
        return storyboardCodegenPrompt(targetConcept, StoryboardJsonBuilder.buildForCodegen(storyboard));
    }

    public static String storyboardCodegenPrompt(String targetConcept, String storyboardJson) {
        return String.format(
                "Target concept: %s\n\n"
                        + "Use the following compact storyboard JSON as the source of truth for staging, object identity, continuity, and scene execution.\n"
                        + "- Treat every object id as a stable visual identity.\n"
                        + "- If an id persists, keep or transform the same mobject instead of redrawing it.\n"
                        + "- If a scene uses `scene_mode = 3d`, use `ThreeDScene`, follow `camera_plan`, and judge layout in projected screen space.\n"
                        + "- Use `screen_overlay_plan` with `add_fixed_in_frame_mobjects` for fixed explanatory text.\n"
                        + "- Respect `safe_area_plan` and dynamic attachment for labels on moving objects.\n\n"
                        + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                targetConcept, storyboardJson);
    }
}
