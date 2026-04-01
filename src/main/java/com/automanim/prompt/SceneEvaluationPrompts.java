package com.automanim.prompt;

import java.util.List;

/**
 * Prompts for Stage 5: geometry-based scene-evaluation fixes.
 */
public final class SceneEvaluationPrompts {

    private static final String SYSTEM =
            "You are fixing Manim code that rendered but has layout issues detected by geometry analysis.\n"
                    + "Preserve the teaching goal, visual intent, scene class name, and continuity.\n"
                    + "Prefer adjusting positioning, scaling, grouping, and spacing over deleting explanatory content.\n"
                    + "For frame repair, use translation/recentering and uniform scaling as the default first-choice strategy before changing geometric constructions or attachment logic.\n"
                    + "Also correct semantically wrong geometric attachments you notice, especially angle markers that are drawn on the wrong side or detached from their true vertex.\n"
                    + "Treat storyboard geometric constraints as hard requirements: if a point is defined as a reflection, midpoint, foot, or intersection, preserve that definition while fixing layout.\n"
                    + "When a constrained construction goes out of frame, prefer recentering or uniformly scaling the whole related diagram, or moving overlays, instead of moving one constrained point independently.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private SceneEvaluationPrompts() {}

    public static String layoutFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 5 / Scene Evaluation Fix",
                "Revise Manim code after geometry-based scene evaluation",
                targetConcept,
                targetDescription,
                true
        ) + SYSTEM);
    }

    public static String layoutFixUserPrompt(String storyboardJson,
                                             String code,
                                             String issueSummary,
                                             String sceneEvaluationJson,
                                             List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code rendered, but post-render scene evaluation found layout issues in sampled frames.\n\n")
                .append(SystemPrompts.STORYBOARD_FIELD_GUIDE_REPAIR)
                .append("\n\nCompact storyboard JSON (source of truth):\n```json\n")
                .append(storyboardJson != null && !storyboardJson.isBlank() ? storyboardJson : "{\"scenes\":[]}")
                .append("\n```\n\n")
                .append("```python\n").append(code).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n\n")
                .append("Repair process requirements:\n")
                .append("1. First identify the affected storyboard scene(s) and the ids/constraints tied to the reported elements.\n")
                .append("2. For overlap and offscreen repair, first try translation/recentering and uniform scaling of the affected overlay or constrained group before changing geometry or redefining attachments.\n")
                .append("3. Fix overlap only through text/overlay layout changes, spacing, grouping, recentering, or uniform scaling of constrained groups.\n")
                .append("4. Fix offscreen issues using `safe_area_plan` and `layout_goal`; do not push text farther off frame just to avoid overlap.\n")
                .append("5. Preserve reflections, symmetry, intersections, equal distances, and anchor-follow relationships exactly.\n\n")
                .append("Please fix the code so the reported sampled frames no longer have elements overlapping or going outside the frame.\n")
                .append("Preserve the intended teaching flow and animation meaning.\n")
                .append("Preserve geometric invariants from the storyboard; do not fix offscreen issues by breaking reflections, symmetry, intersections, equal distances, or other defining constructions.\n")
                .append("Also proactively check for common Python and Manim runtime mistakes.\n")
                .append("Remember: Return ONLY the single Python code block containing the full file. No explanation.\n");

        if (fixHistory != null && !fixHistory.isEmpty()) {
            sb.append("\nPrevious fix attempts to avoid repeating:\n");
            for (int i = 0; i < fixHistory.size(); i++) {
                String item = fixHistory.get(i);
                if (item == null) {
                    continue;
                }
                sb.append("  Attempt ").append(i + 1).append(": ")
                        .append(item.length() > 100 ? item.substring(0, 100) + "..." : item)
                        .append("\n");
            }
        }
        return sb.toString();
    }
}
