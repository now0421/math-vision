package com.automanim.prompt;

import java.util.List;

/**
 * Prompts for Stage 2: code generation and validation fixes.
 */
public final class CodeGenerationPrompts {

    private static final String CODE_GENERATION_SYSTEM =
            "You are an expert Manim Community engineer and Python programmer.\n"
                    + "Generate complete, runnable, maintainable Python code that implements the storyboard.\n"
                    + "Treat the provided storyboard JSON as an execution specification, not loose inspiration.\n\n"
                    + "Mandatory rules:\n"
                    + "- Use `from manim import *`.\n"
                    + "- " + SystemPrompts.ASCII_IDENTIFIER_RULES
                    + "- Preserve scene continuity instead of clearing the scene between beats.\n"
                    + "- Do not store mobjects on `self` just to reuse them across scene methods.\n"
                    + "- Do not hardcode numeric MathTex subobject indexing.\n"
                    + "- Use `ThreeDScene` only when needed and keep overlays fixed in frame when appropriate.\n"
                    + "- Keep content inside x[-7,7], y[-4,4] and prefer stable anchors plus `arrange`/`next_to`.\n"
                    + "- Keep labels dynamically attached to moving objects.\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES
                    + "- " + SystemPrompts.ANGLE_MARKER_RULES
                    + "- Do not place a free-floating arc by shifting/rotating it near the vertex, and do not accidentally mark a large exterior angle when the scene intends two small equal angles.\n"
                    + "- Treat storyboard `geometry_constraints` and object `constraint_note` fields as hard mathematical invariants.\n"
                    + "- " + SystemPrompts.GEOMETRY_CONSTRAINT_RULES + "\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE + "\n"
                    + "Additional storyboard field rules:\n"
                    + "- `continuity_plan` and `global_visual_rules` define global constraints that should shape the whole file.\n"
                    + "- `entering_objects[].id` is a stable visual identity. Reuse the same mobject variable for that id whenever the object persists or is transformed later.\n"
                    + "- `persistent_objects` means those object ids should stay on screen from earlier beats; do not recreate them unless replacement is unavoidable.\n"
                    + "- `exiting_objects` means those object ids should explicitly leave the scene with a clear removal animation when appropriate.\n"
                    + "- `actions` are the main execution plan. Respect their order, targets, and visible intent when deciding the animation sequence.\n"
                    + "- `entering_objects[].content` tells you what must be shown.\n"
                    + "- `notes_for_codegen` are implementation hints and should be followed unless they conflict with Manim correctness.\n"
                    + "- `step_refs`, `title`, and `narration` explain the teaching purpose of the beat and should help you choose clear animation structure.\n\n"
                    + "Continuity and object-management rules:\n"
                    + "- Build a stable object registry in local variables or dictionaries when useful so ids can be reused across beats.\n"
                    + "- Prefer transforming existing mobjects over fading out and redrawing the same concept.\n"
                    + "- Keep a persistent base diagram stable while adding, highlighting, or updating only the necessary layer.\n"
                    + "- When an action targets an existing id, animate that existing object instead of silently creating a duplicate.\n\n"
                    + "Layout and camera rules:\n"
                    + "- Convert `placement`, `camera_anchor`, `camera_plan`, `safe_area_plan`, and `screen_overlay_plan` into concrete Manim layout and camera code.\n"
                    + "- Choose readable absolute coordinates that preserve continuity and keep important content inside the safe frame.\n"
                    + "- Prefer `VGroup`, `arrange`, `next_to`, alignment helpers, and anchored groups over brittle hardcoded coordinates everywhere.\n"
                    + "- If a scene is marked `3d`, use `ThreeDScene`, apply the camera plan explicitly, and keep fixed overlays readable in screen space.\n\n"
                    + "Code quality rules:\n"
                    + "- Return one full runnable file with helper methods when they improve clarity.\n"
                    + "- Use descriptive ASCII variable names derived from storyboard ids or roles.\n"
                    + "- Ensure the generated code clearly reflects the storyboard scene order and action order.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT.replace("corrected", "runnable");

    private static final String VALIDATION_FIX_SYSTEM =
            "You are a Manim code correction specialist.\n"
                    + "You will receive generated Manim code together with validation failures.\n"
                    + "Rewrite the full file so it becomes valid, consistent, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested scene class name, and proactively fix nearby Python/Manim mistakes.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_VALIDATION_FIX_SYSTEM =
            "You are a GeoGebra Classic command correction specialist.\n"
                    + "You will receive generated GeoGebra command code together with static validation failures.\n"
                    + "Rewrite the full command script so it becomes valid, dependency-safe, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested figure naming intent, and proactively fix nearby GeoGebra mistakes.\n"
                    + "Use English GeoGebra command names. Follow GeoGebra naming conventions (uppercase-starting point names, lowercase-starting vector names, no reserved labels). Translate any remaining ASCII-spelled ids to GeoGebra-native math names (`Bprime` → `B'`, `Pstar` → `P_{*}`, `Popt` → `P_{opt}`).\n\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_CODE_GENERATION_SYSTEM =
            "You are an expert GeoGebra Classic engineer.\n"
                    + "Generate complete, dependency-safe GeoGebra command code that implements the storyboard for teaching.\n"
                    + "Treat the storyboard as the source of truth for object identity, geometry meaning, layout intent, and teaching order.\n\n"
                    + "Mandatory rules:\n"
                    + "- Return GeoGebra commands, not Python and not JavaScript.\n"
                    + "- Follow GeoGebra naming conventions: point names must start with an uppercase letter; vector names must start with a lowercase letter; function names use `f(x) = ...` notation; use `_` for subscripts (`A_1`, `s_{AB}`) and `'` for primes (`B'`). Never use GeoGebra reserved labels as object names — this includes `x`, `y`, `z`, `e`, `i`, `pi`, and all built-in math function names such as `sin`, `cos`, `tan`, `exp`, `log`, `ln`, `abs`, `sqrt`, `floor`, `ceil`, `round`, `random`, `arg`, `gamma`, `beta`, `sec`, `csc`, `cot`.\n"
                    + "- Translate storyboard ASCII ids to GeoGebra-native math names: `Bprime` → `B'`, `ABprime` → `AB'`, `Pstar` → `P_{*}`, `Popt` → `P_{opt}`. If the storyboard already uses native names like `B'` or `P_{opt}`, keep them verbatim.\n"
                    + "- Prefer common, stable GeoGebra Classic commands over obscure tricks.\n"
                    + "- Build from base objects to derived objects in a clear dependency chain.\n"
                    + "- Preserve geometric meaning: intersections, reflections, midpoints, perpendiculars, parallels, equal-radius points, and similar constructions must stay dependency-driven.\n"
                    + "- Treat storyboard `geometry_constraints` and object `constraint_note` fields as hard mathematical invariants.\n"
                    + "- Interpret storyboard `behavior` by dependency semantics, not by motion permission: `static` means independently defined base object, not automatically a free point.\n"
                    + "- If a point is constrained to a path or object, construct it as a point on that object or with an equivalent dependency-safe definition. Do not replace it with a free coordinate point.\n"
                    + "- If a point is described as fixed and no dependency is stated, define it as an independent anchor and keep it fixed unless the storyboard explicitly asks for dragging.\n"
                    + "- If a point is described as moving or draggable while constrained, preserve both facts at once: keep the dependency and allow the motion within that dependency.\n"
                    + "- If the storyboard implies a bounded range, encode the bound in the construction itself with a segment, ray, restricted path, or slider domain rather than leaving the object unconstrained.\n"
                    + "- Do not invent unsupported convenience syntax such as `Point(line, x, y)` or similar guessed overloads.\n"
                    + "- When initial placement is requested for a constrained point, choose a dependency-safe construction that starts near that location; never break the constraint just to match the initial coordinates.\n"
                    + "- Ignore timing-only details such as scene duration, but preserve the same teaching order and object-state progression.\n"
                    + "- Use style and visibility commands sparingly and semantically.\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES
                    + "- Keep the script organized in scene order so downstream scene buttons can toggle the right visible objects.\n"
                    + "- For angle markers with `Angle(B, A, C)`, the middle argument is the vertex and the angle is measured counterclockwise from ray AB to ray AC. Choose the point order so the counterclockwise sweep covers the intended small angle sector. For example, to mark the angle between an incoming segment from upper-left and a rightward horizontal at vertex P, use `Angle(rightPoint, P, upperLeftPoint)` so the CCW sweep is the small angle above the line.\n"
                    + "- Use `Angle(...)` with `SetFilling` as the sole method for angle markers and sectors. Never use `CircularArc` for angle marker purposes — it draws decorative arcs unrelated to angle measurement and produces incorrect visual results.\n\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA + "\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT.replace("corrected command script", "GeoGebra command script");

    private CodeGenerationPrompts() {}

    public static String systemPrompt(String targetConcept, String targetDescription) {
        return systemPrompt(targetConcept, targetDescription, "manim");
    }

    public static String systemPrompt(String targetConcept,
                                      String targetDescription,
                                      String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureGeoGebraSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                    "Stage 2 / Code Generation",
                    "Generate executable GeoGebra code",
                    targetConcept,
                    targetDescription,
                    false
            ) + GEOGEBRA_CODE_GENERATION_SYSTEM);
        }
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Generation",
                "Generate executable Manim code",
                targetConcept,
                targetDescription,
                true
        ) + CODE_GENERATION_SYSTEM);
    }

    public static String validationFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated code after validation findings",
                targetConcept,
                targetDescription,
                true
        ) + VALIDATION_FIX_SYSTEM);
    }

    public static String geoGebraValidationFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureGeoGebraSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated GeoGebra commands after validation findings",
                targetConcept,
                targetDescription,
                false
        ) + GEOGEBRA_VALIDATION_FIX_SYSTEM);
    }

    public static String validationFixUserPrompt(String sceneName,
                                                 String generatedCode,
                                                 List<String> violations) {
        return validationFixUserPrompt(sceneName, generatedCode, violations, null);
    }

    public static String validationFixUserPrompt(String sceneName,
                                                 String generatedCode,
                                                 List<String> violations,
                                                 String storyboardJson) {
        String problemList = (violations == null || violations.isEmpty())
                ? "- Validation failed for an unspecified reason."
                : "- " + String.join("\n- ", violations);
        String storyboardBlock = (storyboardJson == null || storyboardJson.isBlank())
                ? ""
                : "Compact storyboard JSON (source of truth):\n```json\n"
                + storyboardJson
                + "\n```\n\n";
        return String.format(
                "The generated Manim code failed validation checks.\n\n"
                        + "%s"
                        + "Required scene class name: %s\n\n"
                        + "Current code:\n```python\n%s\n```\n\n"
                        + "Problems found:\n%s\n\n"
                        + "Rewrite the FULL code so it satisfies all validation rules while preserving the teaching goal.\n"
                        + "If storyboard geometry constraints or derived-object definitions are present, preserve them while fixing validation issues.\n"
                        + "Keep `%s` as the exact scene class name, use ASCII-only Python identifiers, and also fix nearby Python/Manim mistakes.\n"
                        + "Return ONLY the full Python code block.",
                storyboardBlock, sceneName, generatedCode, problemList, sceneName);
    }

    public static String geoGebraValidationFixUserPrompt(String figureName,
                                                         String geoGebraCode,
                                                         List<String> violations,
                                                         String storyboardJson) {
        String problemList = (violations == null || violations.isEmpty())
                ? "- Validation failed for an unspecified reason."
                : "- " + String.join("\n- ", violations);
        String storyboardBlock = (storyboardJson == null || storyboardJson.isBlank())
                ? ""
                : "Compact storyboard JSON (source of truth):\n```json\n"
                + storyboardJson
                + "\n```\n\n";
        return String.format(
                "The generated GeoGebra command script failed static validation checks.\n\n"
                        + "%s"
                        + "Intended figure name: %s\n\n"
                        + "Current code:\n```geogebra\n%s\n```\n\n"
                        + "Problems found:\n%s\n\n"
                        + "Rewrite the FULL command script so it satisfies all validation rules while preserving the teaching goal.\n"
                        + "If storyboard geometry constraints or derived-object definitions are present, preserve them while fixing validation issues.\n"
                        + "Use English GeoGebra command names, follow GeoGebra naming conventions (uppercase points, lowercase vectors, no reserved labels), and preserve the figure naming intent around `%s`.\n"
                        + "Return ONLY the full GeoGebra code block.",
                storyboardBlock, figureName, geoGebraCode, problemList, figureName);
    }
}
