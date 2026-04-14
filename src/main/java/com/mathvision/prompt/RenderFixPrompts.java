package com.mathvision.prompt;

import java.util.Collections;
import java.util.List;

/**
 * Prompts for Stage 4 Code Rendering: render-failure fixes.
 */
public final class RenderFixPrompts {

    private static final String MANIM_SYSTEM =
            "You are a Manim Community debugging expert.\n"
                    + "Fix the code so it renders successfully.\n"
                    + "Preserve the original scene class name and intended animation meaning.\n"
                + "Use root-cause-first repair: identify the first causal traceback error, fix it, then sweep structurally similar code paths in the same file.\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + "Naming rules:\n"
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "Color rules:\n"
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + "Fix the reported root cause systematically, and also correct nearby Python/Manim runtime mistakes.\n"
                    + "Do not store mobjects across scene methods via `self`, do not hardcode MathTex numeric indexing, and keep layout inside x[-7,7], y[-4,4].\n"
                    + SystemPrompts.ANGLE_MARKER_RULES
                    + "Do not break mathematical construction constraints while fixing render issues; derived points should remain derived from their source geometry.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are a GeoGebra Classic debugging expert.\n"
                    + "Fix the GeoGebra command script so every reported failure is resolved when the full script is replayed in order via `evalCommand(...)`.\n"
                    + "Preserve the intended construction meaning, object dependency chain, and storyboard teaching order.\n"
                    + "Use English GeoGebra command names.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + "Naming rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "Color rules:\n"
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + "If you must rename an identifier or introduce a new one, update the commented `SCENE_BUTTONS` script consistently so it still references the final object names.\n"
                    + "Do not output Python, JavaScript, or explanations.\n"
                    + "If a command currently returns false, correct the root cause and also proactively repair nearby dependent commands.\n"
                    + "Do not break geometric constraints while fixing command failures; keep derived objects derived from their source objects.\n\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private RenderFixPrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 4 / Code Rendering",
                "Repair Manim code after render failure",
                targetConcept,
                targetDescription,
                "manim"
        ) + MANIM_SYSTEM);
    }

    public static String geoGebraSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureGeoGebraSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 4 / Code Rendering",
                "Repair GeoGebra commands after render validation failure",
                targetConcept,
                targetDescription,
                "geogebra"
        ) + GEOGEBRA_SYSTEM);
    }

    public static String userPrompt(String generatedCode, String error) {
        return userPrompt(generatedCode, error, null, Collections.emptyList());
    }

    public static String userPrompt(String generatedCode, String error, List<String> fixHistory) {
        return userPrompt(generatedCode, error, null, fixHistory);
    }

    public static String userPrompt(String generatedCode,
                                    String error,
                                    String storyboardJson,
                                    List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following Manim code failed to render:\n\n")
                .append(storyboardJson != null && !storyboardJson.isBlank()
                        ? "Compact storyboard JSON (source of truth):\n```json\n"
                        + storyboardJson + "\n```\n\n"
                        : "")
                .append("```python\n").append(generatedCode).append("\n```\n\n")
                .append("Error output:\n```\n").append(error).append("\n```\n\n")
                .append("Please fix the reported error and also inspect nearby and structurally similar code paths for the same root cause.\n")
                .append("Prioritize fixing the earliest traceback cause instead of patching only downstream timeout symptoms.\n")
                .append("If the storyboard encodes geometric constraints or derived constructions, preserve them while fixing the render failure.\n")
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

    public static String geoGebraUserPrompt(String generatedCode,
                                            String error,
                                            String storyboardJson,
                                            List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following GeoGebra command script failed runtime validation after one full replay pass through `evalCommand(...)`.\n\n")
                .append(storyboardJson != null && !storyboardJson.isBlank()
                        ? "Compact storyboard JSON (source of truth):\n```json\n"
                        + storyboardJson + "\n```\n\n"
                        : "")
                .append("```geogebra\n").append(generatedCode).append("\n```\n\n")
                .append("Validation failure details collected from that full pass:\n```\n").append(error).append("\n```\n\n")
                .append("Please rewrite the FULL command script so all reported failures become valid in one pass and downstream dependent commands remain correct.\n")
                .append("Use English GeoGebra command names and preserve geometric dependency constraints from the storyboard.\n")
                .append("If you rename an identifier or add a new one, also update the commented `SCENE_BUTTONS` script so it stays consistent with the final command script.\n")
                .append("Remember: Return ONLY the single fenced `geogebra` code block. No explanation.\n");

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
