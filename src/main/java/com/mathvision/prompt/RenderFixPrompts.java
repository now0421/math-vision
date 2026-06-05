package com.mathvision.prompt;

import com.mathvision.util.ErrorSummarizer;

import java.util.Collections;
import java.util.List;

/**
 * Prompts for Stage 7 Code Rendering: render-failure fixes.
 */
public final class RenderFixPrompts {

    private static final String MANIM_SYSTEM =
            "You are a Manim Community debugging expert.\n"
                    + "Fix the code so it renders successfully.\n"
                    + "Preserve the original scene class name and intended animation meaning.\n\n"
                    + SystemPrompts.STORYBOARD_REPAIR_REFERENCE_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + "Keep implemented mathematical constructions internally consistent while fixing render issues.\n"
                    + "Treat storyboard scene placement as preferred layout input for non-derived objects, but adjust it when needed to fix runtime failures, offscreen/overlap risk, readability, or internal consistency while preserving structured constraints.\n"
                    + "Treat storyboard `coordinate_bounds` as the authoritative storyboard world-coordinate range: preserve given storyboard coordinates and map them through `Axes`/`NumberPlane` with `axes.c2p(x, y)`. Do not rewrite storyboard coordinates to fit the frame; if content falls outside, prefer widening `coordinate_bounds` or the coordinate mapping. Derived points stay computed from their dependencies.\n"
                    + "Mandatory rules:\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + "Do not store mobjects across scene methods via `self`, do not hardcode MathTex numeric indexing.\n"
                    + "Preserve valid `manim_voiceover` imports, `VoiceoverScene`, `GTTSService`, `set_speech_service`, `with self.voiceover(...)`, Chinese `voiceover_text`, and Chinese visible strings while fixing render failures.\n\n"
                    + "Fix strategy:\n"
                    + "Use root-cause-first repair: identify the first causal traceback error, fix it, then sweep structurally similar code paths in the same file.\n"
                    + "Fix the reported root cause systematically, and also correct nearby Python/Manim runtime mistakes.\n"

                    + SystemPrompts.MANIM_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_SYSTEM =
            "You are a GeoGebra Classic debugging expert.\n"
                    + "Fix the GeoGebra command script so every reported failure is resolved when the full script is replayed in order via `evalCommand(...)`.\n"
                    + "Preserve the intended construction meaning and object dependency chain where they are useful and valid.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_REFERENCE_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + "Keep implemented geometric relationships internally consistent while fixing command failures.\n"
                    + "GeoGebra interactivity is mandatory: preserve storyboard-required movable/draggable objects while fixing runtime failures.\n"
                    + "For storyboard objects with `moves_on_object`, `moves_along_range`, `follows_path`, `slider_driven`, or explicit movable/draggable semantics, preserve the intended interaction affordance.\n"
                    + "Direct draggable constrained points must use documented direct path-point syntax such as `Point(path)` or `PointIn(region)`.\n"
                    + "Do not repair a movable/draggable point by changing it to `Point(path, numericConstant)`, `Point({x, y})`, `Intersect(...)`, `Reflect(...)`, `Midpoint(...)`, or another static/dependent construction.\n"
                    + "If a `SetValue(...)` command fails for a directly draggable path point, remove or rewrite that positioning command; do not make the point non-draggable to satisfy the failed command.\n"
                    + "Treat `Point(path, slider)` as slider-driven rather than direct point dragging; use it only when the storyboard explicitly intends slider interaction and the slider is visible/usable.\n"
                    + "Do not apply `SetFixed(object, true)` to storyboard objects that should remain movable/draggable.\n"
                    + "Treat storyboard scene placement as preferred layout input for non-derived objects, but adjust it when needed to fix runtime failures, viewport safety, readability, or internal consistency while preserving structured constraints.\n"
                    + "Treat storyboard `coordinate_bounds` as the authoritative storyboard world-coordinate view (`SetCoordSystem(x_min, x_max, y_min, y_max)`): preserve given storyboard coordinates and prefer widening `coordinate_bounds` over shrinking it to just enclose elements. Do not rewrite given coordinates; derived points stay computed from their dependencies.\n"
                    + "Use English GeoGebra command names.\n"
                    + "Preserve Chinese learner-facing visible text from storyboard object content; do not translate it to English or pinyin.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.GEOGEBRA_SCENE_DIRECTIVE_RULES
                    + "If you must rename an identifier or introduce a new one, update the commented `SCENE_BUTTONS` script consistently so it still references the final object names.\n"
                    + "Do not output Python, JavaScript, or explanations.\n"
                    + "Fix strategy:\n"
                    + "Use root-cause-first repair: identify the earliest invalid command, undefined dependency, unsupported syntax, or viewport-breaking construction, fix it, then sweep structurally similar command paths in the full script.\n"
                    + "Audit the ENTIRE command script, including construction order, renamed identifiers, dependent commands, scene visibility directives, viewport readability, and helper-object clutter.\n"
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
                "Stage 7 / Code Rendering",
                "Repair " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra commands" : "Manim code") + " after render failure",
                targetConcept,
                targetDescription,
                outputTarget
        ));
    }

    public static String manimUserPrompt(String generatedCode, String error) {
        return manimUserPrompt(generatedCode, error, null, Collections.emptyList(), null, null);
    }

    public static String manimUserPrompt(String generatedCode,
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
                .append("Detailed render error context:\n```text\n").append(error).append("\n```\n\n")
                .append(staticAuditSummary != null && !staticAuditSummary.isBlank()
                        ? "Static preflight findings:\n```text\n" + staticAuditSummary + "\n```\n\n"
                        : "")
                .append("The following Manim code failed to render:\n\n")
                .append("```python\n").append(generatedCode).append("\n```\n\n")
                .append(storyboardJson != null && !storyboardJson.isBlank()
                        ? "Compact storyboard JSON (reference context with preferred scene placement for non-derived objects):\n```json\n"
                        + storyboardJson + "\n```\n\n"
                        : "")
                .append("You MUST audit the ENTIRE file. The detailed render error context is the primary repair evidence, and the actual bugs may be anywhere with the same structural pattern. Do NOT limit your fix to only one line if related code paths share the same issue.\n")
                .append("Prioritize the earliest root cause instead of patching downstream symptoms.\n")
                .append("Sweep all `Text(...)`, `Tex(...)`, and `MathTex(...)` calls whenever the error category suggests text-constructor or LaTeX misuse.\n")
                .append("Use storyboard structured constraints, preferred scene placement, geometric summaries, or derived constructions as repair context; keep the final code internally consistent while fixing the render failure.\n")
                .append("Preserve valid voiceover structure and Chinese learner-facing strings while repairing Manim runtime errors.\n")
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
                        ? "Compact storyboard JSON (reference context with preferred scene placement for non-derived objects):\n```json\n"
                        + storyboardJson + "\n```\n\n"
                        : "")
                .append("Validation failure details collected from that full pass:\n```\n").append(error).append("\n```\n\n")
                .append("The following GeoGebra command script failed runtime validation after one full replay pass through `evalCommand(...)`.\n\n")
                .append("```geogebra\n").append(generatedCode).append("\n```\n\n")
                .append("You MUST audit the ENTIRE command script. The error type and signature are routing hints only; the actual bug may be an earlier construction-order, naming, syntax, visibility, or viewport issue with the same structural pattern.\n")
                .append("Prioritize the earliest root cause instead of patching downstream false-return symptoms.\n")
                .append("Preserve storyboard-required GeoGebra interactivity while fixing runtime failures.\n")
                .append("For movable/draggable storyboard objects or objects with `moves_on_object`, `moves_along_range`, `follows_path`, or `slider_driven`, do not replace direct interaction with a static/dependent construction.\n")
                .append("Direct draggable constrained points must remain `Point(path)` or another documented direct draggable construction such as `PointIn(region)`.\n")
                .append("Never fix a failing `SetValue(...)` by changing a directly draggable path point into `Point(path, numericConstant)`; remove or rewrite the positioning command instead.\n")
                .append("Treat `Point(path, slider)` as slider-driven, and use it only when the storyboard explicitly intends slider interaction and the slider remains visible/usable.\n")
                .append("Do not apply `SetFixed(object, true)` to objects that the storyboard says should remain movable/draggable.\n")
                .append("Sweep dependent commands, renamed identifiers, and the commented `SCENE_BUTTONS` block after every fix so the full script remains consistent in one replay pass.\n")
                .append("Please rewrite the FULL command script so all reported failures become valid in one pass and downstream dependent commands remain correct.\n")
                .append("Use English GeoGebra command names and keep implemented geometric dependencies internally consistent; use storyboard details, including preferred scene placement for non-derived objects, as repair context.\n")
                .append("Preserve Chinese learner-facing visible text from storyboard object content.\n")
                .append("If you rename an identifier or add a new one, also update the commented `SCENE_BUTTONS` script so it stays consistent with the final command script.\n")
                .append("Remember: Return ONLY the single fenced `geogebra` code block. No explanation.\n");

        PromptUtils.appendFixHistory(sb, fixHistory);
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private static String formatErrorType(String error) {
        return ErrorSummarizer.classifyError(error).name();
    }
}
