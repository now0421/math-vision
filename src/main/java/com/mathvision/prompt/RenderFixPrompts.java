package com.mathvision.prompt;

import com.mathvision.util.ErrorSummarizer;

import java.util.Collections;
import java.util.List;

/**
 * Prompts for Stage 4 Code Rendering: render-failure fixes.
 */
public final class RenderFixPrompts {

    private static final String MANIM_SYSTEM =
            "You are a Manim Community debugging expert.\n"
                    + "Fix the code so it renders successfully.\n"
                    + "Preserve the original scene class name and intended animation meaning.\n\n"
                    + "Do not break mathematical construction constraints while fixing render issues; derived points should remain derived from their source geometry.\n"
                    + "Mandatory rules:\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + "Naming rules:\n"
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "Color rules:\n"
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + SystemPrompts.ANGLE_MARKER_RULES
                    + "Do not store mobjects across scene methods via `self`, do not hardcode MathTex numeric indexing, and keep layout inside x[-7,7], y[-4,4].\n\n"
                    + "Fix strategy:\n"
                    + "Use root-cause-first repair: identify the first causal traceback error, fix it, then sweep structurally similar code paths in the same file.\n"
                    + "Fix the reported root cause systematically, and also correct nearby Python/Manim runtime mistakes.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are a GeoGebra Classic debugging expert.\n"
                    + "Fix the GeoGebra command script so every reported failure is resolved when the full script is replayed in order via `evalCommand(...)`.\n"
                    + "Preserve the intended construction meaning, object dependency chain, and storyboard teaching order.\n"
                    + "Do not break geometric constraints while fixing command failures; keep derived objects derived from their source objects.\n"
                    + "Use English GeoGebra command names.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + "Naming rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "Color rules:\n"
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + "If you must rename an identifier or introduce a new one, update the commented `SCENE_BUTTONS` script consistently so it still references the final object names.\n"
                    + "Do not output Python, JavaScript, or explanations.\n"
                    + "If a command currently returns false, correct the root cause and also proactively repair nearby dependent commands.\n"
                    + "\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private RenderFixPrompts() {}

    public static String buildRulesPrompt(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(
                    SystemPrompts.ensureGeoGebraSyntaxManual(GEOGEBRA_SYSTEM));
        }
        return SystemPrompts.buildRulesSection(
                SystemPrompts.ensureManimSyntaxManual(MANIM_SYSTEM));
    }

    public static String buildFixedContextPrompt(String targetConcept,
                                                 String targetDescription,
                                                 String outputTarget) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 4 / Code Rendering",
                "Repair " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra commands" : "Manim code") + " after render failure",
                targetConcept,
                targetDescription,
                outputTarget
        ));
    }

    public static String userPrompt(String generatedCode, String error) {
        return userPrompt(generatedCode, error, null, Collections.emptyList(), null, null);
    }

    public static String userPrompt(String generatedCode,
                                    String error,
                                    String storyboardJson,
                                    List<String> fixHistory,
                                    String errorContextMode,
                                    String staticAuditSummary) {
        String errorType = formatErrorType(error);
        String errorSignature = ErrorSummarizer.summarizeSignature(error);
        StringBuilder sb = new StringBuilder();
        sb.append("Manim render failure detected.\n")
                .append("Error type: ").append(errorType).append("\n");
        if (!errorSignature.isBlank()) {
            sb.append("Primary error signature: ").append(errorSignature).append("\n");
        }
        if (errorContextMode != null && !errorContextMode.isBlank()) {
            sb.append("Error context mode: ").append(errorContextMode).append("\n");
        }
        sb.append("\n")
                .append(storyboardJson != null && !storyboardJson.isBlank()
                        ? "Compact storyboard JSON (source of truth):\n```json\n"
                        + storyboardJson + "\n```\n\n"
                        : "")
                .append(staticAuditSummary != null && !staticAuditSummary.isBlank()
                        ? "Static preflight findings:\n```\n" + staticAuditSummary + "\n```\n\n"
                        : "")
                .append("Error summary:\n```\n").append(error).append("\n```\n\n")
                .append("The following Manim code failed to render:\n\n")
                .append("```python\n").append(generatedCode).append("\n```\n\n")
                .append("You MUST audit the ENTIRE file. The error type and signature below are routing hints only — the actual bugs may be anywhere with the same structural pattern. Do NOT limit your fix to the line mentioned in the traceback.\n")
                .append("Treat the error summary as a routing hint, not as a single-line patch target.\n")
                .append("Sweep all `Text(...)`, `Tex(...)`, and `MathTex(...)` calls whenever the error category suggests text-constructor or LaTeX misuse.\n")
                .append("Prioritize the earliest root-cause category instead of patching downstream symptoms.\n")
                .append("If the storyboard encodes geometric constraints or derived constructions, preserve them while fixing the render failure.\n")
                .append("Also proactively check for common Python and Manim runtime mistakes.\n")
                .append("Remember: Return ONLY the single Python code block containing the full file. No explanation.\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    public static String geoGebraUserPrompt(String generatedCode,
                                            String error,
                                            String storyboardJson,
                                            List<String> fixHistory) {
        String errorType = formatErrorType(error);
        String errorSignature = ErrorSummarizer.summarizeSignature(error);
        StringBuilder sb = new StringBuilder();
        sb.append("GeoGebra runtime validation failure detected.\n")
                .append("Error type: ").append(errorType).append("\n");
        if (!errorSignature.isBlank()) {
            sb.append("Primary error signature: ").append(errorSignature).append("\n");
        }
        sb.append("\n")
                .append(storyboardJson != null && !storyboardJson.isBlank()
                        ? "Compact storyboard JSON (source of truth):\n```json\n"
                        + storyboardJson + "\n```\n\n"
                        : "")
                .append("Validation failure details collected from that full pass:\n```\n").append(error).append("\n```\n\n")
                .append("The following GeoGebra command script failed runtime validation after one full replay pass through `evalCommand(...)`.\n\n")
                .append("```geogebra\n").append(generatedCode).append("\n```\n\n")
                .append("Please rewrite the FULL command script so all reported failures become valid in one pass and downstream dependent commands remain correct.\n")
                .append("Use English GeoGebra command names and preserve geometric dependency constraints from the storyboard.\n")
                .append("If you rename an identifier or add a new one, also update the commented `SCENE_BUTTONS` script so it stays consistent with the final command script.\n")
                .append("Remember: Return ONLY the single fenced `geogebra` code block. No explanation.\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private static String formatErrorType(String error) {
        return ErrorSummarizer.classifyError(error).name();
    }
}
