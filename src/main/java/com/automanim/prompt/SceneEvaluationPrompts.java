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
                    + "Return ONLY the full corrected Python code block.";

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

    public static String layoutFixUserPrompt(String manimCode,
                                             String issueSummary,
                                             String sceneEvaluationJson,
                                             List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code rendered, but post-render scene evaluation found layout issues in sampled frames.\n\n")
                .append("```python\n").append(manimCode).append("\n```\n\n")
                .append("Issue summary:\n```\n").append(issueSummary).append("\n```\n\n")
                .append("Scene evaluation report excerpt:\n```json\n").append(sceneEvaluationJson).append("\n```\n\n")
                .append("Please fix the code so the reported sampled frames no longer have elements overlapping or going outside the frame.\n")
                .append("Preserve the intended teaching flow and animation meaning.\n")
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
