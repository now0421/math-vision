package com.automanim.prompt;

import java.util.Collections;
import java.util.List;

/**
 * Prompts for Stage 4: render-failure fixes.
 */
public final class RenderFixPrompts {

    private static final String SYSTEM =
            "You are a Manim Community debugging expert.\n"
                    + "Fix the code so it renders successfully.\n"
                    + "Return ONE SINGLE ```python ... ``` block containing the FULL corrected code and nothing else.\n"
                    + "Preserve the original scene class name and intended animation meaning.\n"
                    + "Use ASCII-only identifiers, fix the reported root cause systematically, and also correct nearby Python/Manim runtime mistakes.\n"
                    + "Do not store mobjects across scene methods via `self`, do not hardcode MathTex numeric indexing, and keep layout inside x[-7,7], y[-4,4].";

    private RenderFixPrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 4 / Render Fix",
                "Repair Manim code after render failure",
                targetConcept,
                targetDescription,
                true
        ) + SYSTEM);
    }

    public static String userPrompt(String code, String error) {
        return userPrompt(code, error, Collections.emptyList());
    }

    public static String userPrompt(String code, String error, List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code failed to render:\n\n")
                .append("```python\n").append(code).append("\n```\n\n")
                .append("Error output:\n```\n").append(error).append("\n```\n\n")
                .append("Please fix the reported error and also inspect nearby and structurally similar code paths for the same root cause.\n")
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
