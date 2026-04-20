package com.mathvision.prompt;

import java.util.List;

/**
 * Prompts for Stage 2: code generation and validation fixes.
 */
public final class CodeGenerationPrompts {

    private static final String MANIM_CODE_GENERATION_SYSTEM =
            "You are an expert Manim Community engineer and Python programmer.\n"
                    + "Generate complete, runnable, maintainable Python code that implements the storyboard.\n"
                    + "Treat the provided storyboard JSON as an execution specification, not loose inspiration.\n\n"
                    + "Mandatory rules:\n"
                    + "- Use `from manim import *`.\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.MANIM_MOTION_AND_PACING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.MANIM_ANIMATION_SELECTION_RULES
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                    + SystemPrompts.MANIM_TYPOGRAPHY_SCALE
                    + SystemPrompts.OPACITY_LEVELS
                    + SystemPrompts.MANIM_TIMING_REFERENCE
                    + SystemPrompts.MANIM_SCENE_TRANSITION_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "- Preserve scene continuity instead of clearing the scene between beats.\n"
                    + "- Do not store mobjects on `self` just to reuse them across scene methods.\n"
                    + "- Do not hardcode numeric MathTex subobject indexing.\n"
                    + "- Use `ThreeDScene` only when needed and keep overlays fixed in frame when appropriate.\n"
                    + "- Keep content inside the readable safe frame and prefer stable anchors plus `arrange`/`next_to`.\n"
                    + "- Do not invent learner-visible objects that are not declared in the storyboard.\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES
                    + "- " + SystemPrompts.ANGLE_MARKER_RULES
                    + "- Do not place a free-floating arc by shifting/rotating it near the vertex, and do not accidentally mark a large exterior angle when the scene intends two small equal angles.\n"
                    + "- Treat storyboard `geometry_constraints` and object `constraint_note` fields as hard mathematical invariants.\n"
                    + "- " + SystemPrompts.GEOMETRY_CONSTRAINT_RULES + "\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_FULL + "\n"
                    + "Additional storyboard field rules:\n"
                    + "- `continuity_plan` and `global_visual_rules` define global constraints that should shape the whole file.\n"
                    + "- `entering_objects[].id` is a stable visual identity. Reuse the same mobject variable for that id whenever the object persists or is transformed later.\n"
                    + "- `persistent_objects` entries describe objects that stay on screen from earlier beats; reuse the same mobject unless replacement is unavoidable.\n"
                    + "- `exiting_objects` entries are id-only removals; make those objects leave the scene clearly when appropriate.\n"
                    + "- `actions` are the main execution plan. Respect their order, targets, and visible intent when deciding the animation sequence and beat timing.\n"
                    + "- `entering_objects[].content` tells you what must be shown.\n"
                    + "- When `content`, `dependency_note`, or related fields mention another object, treat those mentions as object ids only rather than as repeated type declarations.\n"
                    + "- `notes_for_codegen` are implementation hints and should be followed unless they conflict with Manim correctness.\n"
                    + "- `step_refs`, `title`, and `narration` explain the teaching purpose of the beat and should help you choose clear animation structure.\n"
                    + "- If a storyboard object uses `behavior = follows_anchor`, `derived`, or an equivalent dependency note, implement that relationship continuously with the appropriate Manim mechanism.\n\n"
                    + "Continuity and object-management rules:\n"
                    + "- Build a stable object registry in local variables or dictionaries when useful so ids can be reused across beats.\n"
                    + "- Prefer transforming existing mobjects over fading out and redrawing the same concept.\n"
                    + "- Keep a persistent base diagram stable while adding, highlighting, or updating only the necessary layer.\n"
                    + "- When an action targets an existing id, animate that existing object instead of silently creating a duplicate.\n"
                    + "- Use clean exits for temporary annotations, comparisons, and overlays rather than leaving them to accumulate.\n\n"
                    + "Layout and camera rules:\n"
                    + "- Convert structured `placement`, `camera_anchor`, `camera_plan`, `safe_area_plan`, and `screen_overlay_plan` into concrete Manim layout and camera code.\n"
                    + "- Choose readable absolute coordinates that preserve continuity and keep important content inside the safe frame with at least 0.5 units of clearance from every edge.\n"
                    + "- Prefer `Group`/`VGroup`, `arrange`, `next_to`, alignment helpers, and anchored groups over brittle hardcoded coordinates everywhere.\n"
                    + "- If a scene is marked `3d`, use `ThreeDScene`, apply the camera plan explicitly, and keep fixed overlays readable in screen space.\n\n"
                    + "Code quality rules:\n"
                    + "- Return one full runnable file with helper methods when they improve clarity.\n"
                    + "- Use descriptive ASCII variable names derived from storyboard ids or roles.\n"
                    + "- Ensure the generated code clearly reflects the storyboard scene order and action order.\n"
                    + "- Use subtitle-ready beats for major reveals when narration alignment matters.\n\n"
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT.replace("corrected", "runnable");

    private static final String MANIM_VALIDATION_FIX_SYSTEM =
            "You are a Manim code correction specialist.\n"
                    + "You will receive generated Manim code together with validation failures.\n"
                    + "Rewrite the full file so it becomes valid, consistent, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested scene class name, and proactively fix nearby Python/Manim mistakes.\n\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + SystemPrompts.PYTHON_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_VALIDATION_FIX_SYSTEM =
            "You are a GeoGebra Classic command correction specialist.\n"
                    + "You will receive generated GeoGebra command code together with static validation failures.\n"
                    + "Rewrite the full command script so it becomes valid, dependency-safe, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested figure naming intent, and proactively fix nearby GeoGebra mistakes.\n"
                    + "Use English GeoGebra command names.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + "Naming rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES + "\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_CODE_GENERATION_SYSTEM =
            "You are an expert GeoGebra Classic engineer.\n"
                    + "Generate complete, dependency-safe GeoGebra command code that implements the storyboard for teaching.\n"
                    + "Treat the storyboard as the source of truth for object identity, geometry meaning, layout intent, and teaching order.\n\n"
                    + "Mandatory rules:\n"
                    + "- Return GeoGebra commands, not Python and not JavaScript.\n"
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.OPACITY_LEVELS
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "- Prefer common, stable GeoGebra Classic commands over obscure tricks.\n"
                    + "- Build from base objects to derived objects in a clear dependency chain.\n"
                    + "- Preserve geometric meaning: intersections, reflections, midpoints, perpendiculars, parallels, equal-radius points, and similar constructions must stay dependency-driven.\n"
                    + "- Treat storyboard `geometry_constraints` and object `constraint_note` fields as hard mathematical invariants.\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES
                    + "- Interpret storyboard `behavior` by dependency semantics, not by motion permission: `static` means independently defined base object, not automatically a free point.\n"
                    + "- If a point is constrained to a path or object, construct it as a point on that object or with an equivalent dependency-safe definition. Do not replace it with a free coordinate point.\n"
                    + "- If a point is described as fixed and no dependency is stated, define it as an independent anchor and keep it fixed unless the storyboard explicitly asks for dragging.\n"
                    + "- If a point is described as moving or draggable while constrained, preserve both facts at once: keep the dependency and allow the motion within that dependency.\n"
                    + "- If the storyboard implies a bounded range, encode the bound in the construction itself with a segment, ray, restricted path, or slider domain rather than leaving the object unconstrained.\n"
                    + "- Do not invent unsupported convenience syntax such as `Point(line, x, y)` or similar guessed overloads.\n"
                    + "- When initial structured placement is requested for a constrained point, choose a dependency-safe construction that starts near that location or inside the requested range; never break the constraint just to match the coordinates.\n"
                    + "- Ignore timing-only details such as scene duration, but preserve the same teaching order and object-state progression.\n"
                    + "- Use style and visibility commands sparingly and semantically, and apply scripting commands after construction commands.\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES
                    + "- Keep the script organized in scene order so downstream scene buttons can toggle the right visible objects.\n"
                    + "- If a requested visual effect would require a command not documented in the manual, re-express it with documented commands or omit that unsupported decoration.\n\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA + "\n"
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "Additional storyboard field rules:\n"
                    + "- When `content`, `dependency_note`, or other object fields mention another object, treat those mentions as object ids only. Do not reinterpret kind words from prose and do not invent a second object type for the same id.\n"
                    + "- Treat storyboard object ids as the naming source for generated GeoGebra variables. Preserve those ids in code, and when you must introduce a helper name, use concise camelCase or math-style identifiers.\n\n"
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
                    "geogebra"
            ) + GEOGEBRA_CODE_GENERATION_SYSTEM);
        }
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Generation",
                "Generate executable Manim code",
                targetConcept,
                targetDescription,
                "manim"
        ) + MANIM_CODE_GENERATION_SYSTEM);
    }

    public static String validationFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureManimSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated code after validation findings",
                targetConcept,
                targetDescription,
                "manim"
        ) + MANIM_VALIDATION_FIX_SYSTEM);
    }

    public static String geoGebraValidationFixSystemPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.ensureGeoGebraSyntaxManual(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated GeoGebra commands after validation findings",
                targetConcept,
                targetDescription,
                "geogebra"
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
                        + "Keep `%s` as the exact scene class name.\n"
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
                        + "Use English GeoGebra command names and preserve the figure naming intent around `%s`.\n"
                        + "Return ONLY the full GeoGebra code block.",
                storyboardBlock, figureName, geoGebraCode, problemList, figureName);
    }

    /**
     * Builds the user prompt for generating the code skeleton
     * (imports, class, construct() that calls scene methods, shared helpers).
     */
    public static String skeletonUserPrompt(String storyboardJson,
                                            java.util.List<String> sceneMethodNames) {
        String methodList = String.join(", ", sceneMethodNames);
        return String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Generate ONLY the code skeleton for a single-file Manim animation:\n"
                        + "- `from manim import *` and any other needed imports\n"
                        + "- Constants and shared helper functions if needed\n"
                        + "- `class MainScene(Scene):` (or ThreeDScene if any scene uses 3d)\n"
                        + "- `def construct(self):` that calls these scene methods in order: %s\n"
                        + "- Empty method stubs `def %s(self): pass` for each scene method\n\n"
                        + "Do NOT implement the scene methods yet — just provide the skeleton with `pass` stubs.\n"
                        + "Return the skeleton code via the write_code_skeleton tool.",
                storyboardJson, methodList,
                sceneMethodNames.get(0) + "`, `def " + sceneMethodNames.get(sceneMethodNames.size() - 1));
    }

    /**
     * Builds the user prompt for generating a single scene method body.
     */
    public static String sceneCodeUserPrompt(String sceneJson,
                                             String methodName,
                                             int sceneIndex,
                                             int totalScenes) {
        return String.format(
                "Now implement scene method `%s` (scene %d of %d).\n\n"
                        + "Scene specification:\n```json\n%s\n```\n\n"
                        + "Generate the COMPLETE method body for `def %s(self):`.\n"
                        + "- Include the full `def %s(self):` signature and all code inside.\n"
                        + "- Use variables and objects established in earlier scene methods via `self` if needed.\n"
                        + "- Follow the storyboard actions, entering/persistent/exiting objects exactly.\n"
                        + "- Return the method code via the write_scene_code tool.",
                methodName, sceneIndex + 1, totalScenes,
                sceneJson, methodName, methodName);
    }

    /**
     * Builds the user prompt for generating the GeoGebra code skeleton
     * (global setup commands, shared definitions, coordinate system).
     */
    public static String geoGebraSkeletonUserPrompt(String storyboardJson,
                                                     List<String> sceneSectionNames) {
        String sectionList = String.join(", ", sceneSectionNames);
        return String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Generate ONLY the GeoGebra code skeleton (setup section):\n"
                        + "- Global coordinate and view settings if needed\n"
                        + "- Shared base objects that persist across multiple scenes\n"
                        + "- A section comment header for each scene: %s\n\n"
                        + "Do NOT implement the scene-specific objects yet — just provide the global setup.\n"
                        + "Return the skeleton code via the write_code_skeleton tool.",
                storyboardJson, sectionList);
    }

    /**
     * Builds the user prompt for generating a single GeoGebra scene section.
     */
    public static String geoGebraSceneCodeUserPrompt(String sceneJson,
                                                      String sceneSectionName,
                                                      int sceneIndex,
                                                      int totalScenes) {
        return String.format(
                "Now implement scene section \"%s\" (scene %d of %d).\n\n"
                        + "Scene specification:\n```json\n%s\n```\n\n"
                        + "Generate the COMPLETE GeoGebra command block for this scene:\n"
                        + "- Start with a comment line: # %s\n"
                        + "- Create all entering objects for this scene\n"
                        + "- Apply styles and visibility settings\n"
                        + "- Reference shared objects from the skeleton by their established names\n"
                        + "- Return the scene code via the write_scene_code tool.",
                sceneSectionName, sceneIndex + 1, totalScenes,
                sceneJson, sceneSectionName);
    }
}
