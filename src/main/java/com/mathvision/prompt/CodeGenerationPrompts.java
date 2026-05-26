package com.mathvision.prompt;

import java.util.List;

/**
 * Prompts for Stage 2: code generation and validation fixes.
 */
public final class CodeGenerationPrompts {

    private static final String MANIM_CODE_GENERATION_SYSTEM =
            "You are an expert Manim Community engineer and Python programmer.\n"
                    + "Generate complete, runnable, maintainable Python code that implements the storyboard.\n"
                    + "Treat the provided storyboard JSON as an execution specification, but distinguish canonical object semantics from per-scene visual-state patches.\n\n"
                    + SystemPrompts.STORYBOARD_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + "Mandatory rules:\n"
                    + "- Use `from manim import *`.\n"
                    + "- Do not invent learner-visible objects that are not declared in the storyboard. This includes point labels, line labels, captions, formula badges, helper overlays, angle labels, and explanatory text.\n"
                    + "- If clarity seems to require an undeclared label or annotation, omit it rather than creating it; only storyboard-declared visible objects may appear on screen.\n"
                    + "- Do not treat `style.label_visible` as permission to create a label. Render labels only when the storyboard declares explicit text/equation label objects.\n"
                    + "- Preserve scene continuity instead of clearing the scene between beats.\n"
                    + "- Treat storyboard structured `constraints` and scene `notes_for_codegen` fields as hard invariants.\n"
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_MANIM + "\n"
                    + "Additional code generation rules:\n"
                    + "- `object_registry[].content` describes candidate visible content; implement only storyboard-declared objects that are necessary or helpful for the teaching beat.\n"
                    + "- Treat concise mathematical label content literally. If a `kind=text` object content is `B′`, `l`, `AB`, or similar, render exactly that label and do not expand it into descriptive visible prose such as `反射点B′`.\n"
                    + "- When registry `content`, constraint refs, or related fields mention another object, treat those mentions as object ids only rather than as repeated type declarations.\n"
                    + "- If structured constraints define attachment, motion, or derived geometry, implement that relationship continuously with the appropriate Manim mechanism.\n"
                    + "- If an object's actual coordinates are derived from other objects, recompute its coordinates from those source objects or use native backend/API construction helpers. Use scene placement as the preferred initial visual state for independent coordinates and non-derived constrained objects; adjust it only when needed for safe layout, readability, or consistency.\n\n"
                    + "- Apply every `notes_for_codegen` item as a mandatory scene-level implementation constraint. If a note gives a concrete range, endpoint, duration, visibility, lifecycle, transform, color, or layout instruction, encode that exact constraint in code rather than replacing it with a similar-looking free movement or ad hoc placement.\n\n"
                    + "Continuity and object-management rules:\n"
                    + "- Maintain a stable id-to-mobject dictionary on the scene, for example `self.objects = {}` in `construct()` or the first scene method, and store each storyboard id as `self.objects[\"id\"] = mobject` when it is first created.\n"
                    + "- Reuse existing objects only by storyboard id, e.g. `P = self.objects[\"P\"]`; never infer semantic identity from `self.mobjects[index]`, `self.mobjects[-1]`, creation order, or list position.\n"
                    + "- `self.mobjects` may be used only for non-semantic whole-scene operations such as `Group(*self.mobjects)` during a complete cleanup; do not use it to retrieve A, B, labels, points, segments, formulas, or markers.\n"
                    + "- Prefer transforming existing mobjects over fading out and redrawing the same concept.\n"
                    + "- When the storyboard keeps a base diagram persistent, keep its identity stable while adding, highlighting, dimming, or updating only the necessary layer.\n"
                    + "- When an action targets an existing id, animate that existing object from `self.objects` instead of silently creating a duplicate.\n"
                    + "- Follow the storyboard's lifecycle decisions: persistent objects may remain visible, dimmed, transformed, or repositioned as the scene requires; exiting objects should leave cleanly unless that would break continuity or a hard constraint. Do not add extra exits merely to reduce density.\n\n"
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES + "\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + "- Implement the visual plan with documented Manim constructs and no hidden assumptions.\n"
                    + "- " + SystemPrompts.MANIM_TEXT_CONSTRUCTOR_MAPPING
                    + "- " + SystemPrompts.MANIM_ANGLE_MARKER_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_CODEGEN_RULES
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.MANIM_MOTION_AND_PACING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.MANIM_ANIMATION_SELECTION_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                    + SystemPrompts.MANIM_TYPOGRAPHY_SCALE
                    + SystemPrompts.OPACITY_LEVELS
                    + SystemPrompts.MANIM_TIMING_REFERENCE
                    + SystemPrompts.MANIM_SCENE_TRANSITION_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "- Keep storyboard mobjects that persist across scene methods in `self.objects` by storyboard id; this is the required cross-scene identity store, not an optional convenience.\n"
                    + "- Do not hardcode numeric MathTex subobject indexing.\n"
                    + "- Use `ThreeDScene` only when needed and keep overlays fixed in frame when appropriate.\n"
                    + "- Keep content inside the readable safe frame and prefer stable anchors plus `arrange`/`next_to`.\n"
                    + "- " + SystemPrompts.COLOR_FORMAT_RULES
                    + "- " + SystemPrompts.MANIM_COLOR_RULES
                    + "- Do not place a free-floating angle, right-angle, or arc sweep marker by shifting/rotating it near the vertex/anchor, and do not accidentally mark a large exterior angle when the scene intends two small equal angles.\n"
                    + "Layout and camera rules:\n"
                    + "- Convert structured `placement`, `camera_anchor`, `camera_plan`, `safe_area_plan`, and `screen_overlay_plan` into concrete Manim layout and camera code.\n"
                    + "- Choose readable absolute coordinates that preserve continuity and keep important content inside the safe frame with at least 0.5 units of clearance from every edge.\n"
                    + "- Prefer `Group`/`VGroup`, `arrange`, `next_to`, alignment helpers, and anchored groups over brittle hardcoded coordinates everywhere.\n"
                    + "- If a scene is marked `3d`, use `ThreeDScene`, apply the camera plan explicitly, and keep fixed overlays readable in screen space.\n\n"
                    + "Code quality rules:\n"
                    + "- Return one full runnable file with helper methods when they improve clarity.\n"
                    + "- Use descriptive ASCII variable names derived from storyboard ids or roles.\n"
                    + "- Ensure the generated code clearly reflects the storyboard scene order and action order.\n"
                    + "- When implementing at least one selected important `voiceover_text`, use `VoiceoverScene`, import `manim_voiceover` and `GTTSService`, define `VOICEOVER_SPEED`, call `self.set_speech_service(GTTSService(lang=\"zh-CN\", global_speed=VOICEOVER_SPEED))`, and synchronize selected important narrated beats with `with self.voiceover(text=...) as tracker:`.\n"
                    + "- Do not implement every `voiceover_text` mechanically. Treat storyboard narration like storyboard elements: select the important narrations that clarify major setup, conceptual turns, non-obvious constructions, important reveals, comparisons, or conclusions, and omit routine narration that would make the animation verbose.\n"
                    + "- For selected voiced beats with animation, set animation run time from `tracker.duration` unless storyboard timing gives a stricter visual constraint; for selected voiced beats without animation, wait for `tracker.duration`.\n"
                    + "- Preserve selected Chinese `voiceover_text` and Chinese visible object content exactly unless shortening is required for readability.\n"
                    + "- Use subtitle-ready beats for major reveals when narration alignment matters.\n\n"
                    + SystemPrompts.MANIM_CODE_OUTPUT_FORMAT.replace("corrected", "runnable");

    private static final String MANIM_VALIDATION_FIX_SYSTEM =
            "You are a Manim code correction specialist.\n"
                    + "You will receive generated Manim code together with validation failures.\n"
                    + "Rewrite the full file so it becomes valid, consistent, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested scene class name, and proactively fix nearby Python/Manim mistakes.\n\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + "- Do not treat `style.label_visible` as permission to create a label. Render labels only when the storyboard declares explicit text/equation label objects.\n"
                    + SystemPrompts.MANIM_MANUAL_ONLY_RULES
                    + SystemPrompts.MANIM_CODE_HYGIENE_RULES
                    + SystemPrompts.COMMON_RENDER_FAILURE_GUARDRAILS
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_CODEGEN_RULES
                    + SystemPrompts.MANIM_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_VALIDATION_FIX_SYSTEM =
            "You are a GeoGebra Classic command correction specialist.\n"
                    + "You will receive generated GeoGebra command code together with static validation failures.\n"
                    + "Rewrite the full command script so it becomes valid, dependency-safe, and ready for the next workflow stage.\n"
                    + "Fix every reported validation problem, preserve the teaching content, keep the requested figure naming intent, and proactively fix nearby GeoGebra mistakes.\n"
                    + "Preserve Chinese learner-facing visible text from storyboard object content; use English GeoGebra command names and ASCII backend identifiers.\n"
                    + "Use English GeoGebra command names.\n"
                    + SystemPrompts.STORYBOARD_REPAIR_AUTHORITY_RULES
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.GEOGEBRA_ANGLE_MARKER_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_CODEGEN_RULES
                    + "Naming rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES + "\n"
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT;

    private static final String GEOGEBRA_CODE_GENERATION_SYSTEM =
            "You are an expert GeoGebra Classic engineer.\n"
                    + "Generate complete, dependency-safe GeoGebra command code that implements the storyboard for teaching.\n"
                    + "Treat object_registry as the semantic authority for object identity, geometry meaning, and dependency relationships; treat scene placement/style as momentary visual-state guidance.\n\n"
                    + SystemPrompts.STORYBOARD_AUTHORITY_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + "Mandatory rules:\n"
                    + "- Return GeoGebra commands, not Python and not JavaScript.\n"
                    + "- Build from base objects to derived objects in a clear dependency chain.\n"
                    + "- Preserve geometric meaning: intersections, reflections, midpoints, perpendiculars, parallels, equal-radius points, and similar constructions must stay dependency-driven.\n"
                    + "- Treat storyboard structured `constraints` and scene `notes_for_codegen` fields as hard invariants.\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_RULES
                    + SystemPrompts.STORYBOARD_FIELD_GUIDE_GEOGEBRA + "\n"
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "Additional storyboard field rules:\n"
                    + "- When `content`, constraint refs, or other object fields mention another object, treat those mentions as object ids only. Do not reinterpret kind words from prose and do not invent a second object type for the same id.\n"
                    + "- Treat storyboard object ids as the naming source for generated GeoGebra variables. Preserve those ids in code, and when you must introduce a helper name, use concise camelCase or math-style identifiers.\n\n"
                    + "- Use structured constraints to decide dependency semantics: independent anchors may be free/fixed, while constrained or derived objects must remain dependency-driven.\n"
                    + "- If a point is constrained to a path or object, construct it as a point on that object or with an equivalent dependency-safe definition. Do not replace it with a free coordinate point.\n"
                    + "- For GeoGebra direct draggable constrained points, use `Point(path)` or `PointIn(region)` as documented. Do not use undocumented `PointOn` wording or guessed overloads.\n"
                    + "- If a point is described as fixed and no dependency is stated, define it as an independent anchor and keep it fixed unless the storyboard explicitly asks for dragging.\n"
                    + "- If a point is described as moving or draggable while constrained, preserve both facts at once: keep the dependency and allow the motion within that dependency.\n"
                    + "- If a storyboard point has `moves_on_object`, `moves_along_range`, or is described as movable/draggable, preserve a direct interaction affordance unless the storyboard explicitly requests slider control.\n"
                    + "- If the storyboard implies a bounded range, encode the bound in the construction itself with a segment, ray, restricted path, or slider domain rather than leaving the object unconstrained.\n"
                    + "- For a bounded draggable point, create a bounded path object such as a Segment, then define the point with `Point(path)`. Do not define it with `Point(path, numericConstant)`.\n"
                    + "- `Point(path, numericConstant)` creates a dependent point at a fixed path parameter; it is not a directly draggable point and must not implement a movable/draggable storyboard point.\n"
                    + "- `Point(path, slider)` is slider-driven, not direct point dragging; use it only when the storyboard explicitly intends slider interaction and the slider is visible/usable.\n"
                    + "- Apply every `notes_for_codegen` item as a mandatory scene-level implementation constraint. If a note gives a concrete range, endpoint, visibility, lifecycle, style, or layout instruction, encode that exact constraint in the command script.\n"
                    + "- If an object's actual coordinates are derived from other objects, recompute its coordinates from those source objects or use native GeoGebra construction commands. Use scene placement as the preferred initial visual state for independent coordinates and non-derived constrained objects; adjust it only when needed for safe layout, readability, or consistency.\n"
                    + "- Do not invent unsupported convenience syntax such as `Point(line, x, y)` or similar guessed overloads.\n"
                    + "- When initial structured placement is requested for a constrained point, choose a dependency-safe construction that starts near that location or inside the requested range; never break the constraint just to match the coordinates.\n"
                    + SystemPrompts.GEOGEBRA_VIEWPORT_RULES
                    + SystemPrompts.GEOGEBRA_MANUAL_ONLY_RULES
                    + SystemPrompts.GEOGEBRA_ANGLE_MARKER_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_CODEGEN_RULES
                    + "- Prefer common, stable GeoGebra Classic commands over obscure tricks.\n"
                    + "- Ignore timing-only details such as scene duration, but preserve the same teaching order and object-state progression.\n"
                    + "- Use style and visibility commands sparingly and semantically, and apply scripting commands after construction commands.\n"
                    + "- " + SystemPrompts.COLOR_FORMAT_RULES
                    + "- " + SystemPrompts.GEOGEBRA_COLOR_RULES
                    + "- Keep the script organized in scene order so downstream scene buttons can toggle the right visible objects.\n"
                    + "- If a requested visual effect would require a command not documented in the manual, re-express it with documented commands or omit that unsupported decoration.\n\n"
                    + "- Do not add specific GeoGebra command names from storyboard notes unless they are documented in the active syntax manual; implement unsupported effects generically with documented commands instead.\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.OPACITY_LEVELS
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + SystemPrompts.GEOGEBRA_CODE_OUTPUT_FORMAT.replace("corrected command script", "GeoGebra command script");

    private CodeGenerationPrompts() {}

    public static String buildRulesPrompt(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.buildRulesSection(
                    SystemPrompts.ensureGeoGebraSyntaxManual(GEOGEBRA_CODE_GENERATION_SYSTEM));
        }
        return SystemPrompts.buildRulesSection(
                SystemPrompts.ensureManimSyntaxManual(MANIM_CODE_GENERATION_SYSTEM));
    }

    public static String buildFixedContextPrompt(String targetConcept,
                                                 String targetDescription,
                                                 String outputTarget,
                                                 String objectRegistryJson) {
        String registrySection = "";
        if (objectRegistryJson != null && !objectRegistryJson.isBlank()) {
            registrySection = "\n\nObject registry (complete JSON - semantic authority for object identity, geometry meaning, and dependency relationships):\n```json\n"
                    + objectRegistryJson + "\n```";
        }
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Generation",
                "Generate executable " + ("geogebra".equalsIgnoreCase(outputTarget) ? "GeoGebra code" : "Manim code"),
                targetConcept,
                targetDescription,
                outputTarget
        ) + registrySection);
    }

    public static String buildManimValidationFixRulesPrompt() {
        return SystemPrompts.buildRulesSection(
                SystemPrompts.ensureManimSyntaxManual(MANIM_VALIDATION_FIX_SYSTEM));
    }

    public static String buildManimValidationFixFixedContextPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated code after validation findings",
                targetConcept,
                targetDescription,
                "manim"
        ));
    }

    public static String buildGeoGebraValidationFixRulesPrompt() {
        return SystemPrompts.buildRulesSection(
                SystemPrompts.ensureGeoGebraSyntaxManual(GEOGEBRA_VALIDATION_FIX_SYSTEM));
    }

    public static String buildGeoGebraValidationFixFixedContextPrompt(String targetConcept, String targetDescription) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 2 / Code Fix",
                "Repair generated GeoGebra commands after validation findings",
                targetConcept,
                targetDescription,
                "geogebra"
        ));
    }

    public static String manimValidationFixUserPrompt(String sceneName,
                                                 String generatedCode,
                                                 List<String> violations) {
        return manimValidationFixUserPrompt(sceneName, generatedCode, violations, null);
    }

    public static String manimValidationFixUserPrompt(String sceneName,
                                                 String generatedCode,
                                                 List<String> violations,
                                                 String storyboardJson) {
        String problemList = (violations == null || violations.isEmpty())
                ? "- Validation failed for an unspecified reason."
                : "- " + String.join("\n- ", violations);
        String storyboardBlock = (storyboardJson == null || storyboardJson.isBlank())
                ? ""
                : "Compact storyboard JSON (semantic authority):\n```json\n"
                + storyboardJson
                + "\n```\n\n";
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "The generated Manim code failed validation checks.\n\n"
                        + "%s"
                        + "Required scene class name: %s\n\n"
                        + "Current code:\n```python\n%s\n```\n\n"
                        + "Problems found:\n%s\n\n"
                        + "Rewrite the FULL code so it satisfies all validation rules while preserving the teaching goal.\n"
                        + "If storyboard structured constraints, geometry constraints, or derived-object definitions are present, preserve them while fixing validation issues.\n"
                        + "Treat `notes_for_codegen` as hard scene-level constraints; preserve every concrete range, endpoint, lifecycle, visibility, transform, color, and layout instruction unless it is unsupported, in which case preserve the same intent with a documented equivalent.\n"
                        + "Apply text constructor mapping consistently across the file: `kind=equation -> MathTex`, `kind=text/text_card -> Text` unless the content clearly requires math rendering, and avoid `Tex` except for explicit non-math LaTeX text.\n"
                        + "Preserve Chinese visible object content. Preserve only the selected important `voiceover_text` items that remain implemented; when voiceover is present, keep the `VoiceoverScene`/`GTTSService`/`self.voiceover(...)` structure synchronized with those selected action beats.\n"
                        + "Keep `%s` as the exact scene class name.\n"
                        + "Return ONLY the full Python code block.",
                storyboardBlock, sceneName, generatedCode, problemList, sceneName));
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
                : "Compact storyboard JSON (semantic authority):\n```json\n"
                + storyboardJson
                + "\n```\n\n";
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "The generated GeoGebra command script failed static validation checks.\n\n"
                        + "%s"
                        + "Intended figure name: %s\n\n"
                        + "Current code:\n```geogebra\n%s\n```\n\n"
                        + "Problems found:\n%s\n\n"
                        + "Rewrite the FULL command script so it satisfies all validation rules while preserving the teaching goal.\n"
                        + "If storyboard structured constraints, geometry constraints, or derived-object definitions are present, preserve them while fixing validation issues.\n"
                        + "Treat `notes_for_codegen` as hard scene-level constraints; preserve every concrete range, endpoint, lifecycle, visibility, style, and layout instruction unless it is unsupported, in which case preserve the same intent with a documented equivalent.\n"
                        + "Use English GeoGebra command names and preserve the figure naming intent around `%s`.\n"
                        + "Preserve Chinese learner-facing visible text from storyboard object content; do not translate it to English or pinyin.\n"
                        + "Return ONLY the full GeoGebra code block.",
                storyboardBlock, figureName, geoGebraCode, problemList, figureName));
    }

    /**
     * Builds the user prompt for generating the code skeleton
     * (imports, class, construct() that calls scene methods, shared helpers).
     */
    public static String manimSkeletonUserPrompt(String storyboardJson,
                                            java.util.List<String> sceneMethodNames) {
        String methodList = String.join(", ", sceneMethodNames);
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Generate ONLY the code skeleton for a single-file Manim animation:\n"
                        + "- `from manim import *` and any other needed imports; include `manim_voiceover` and `GTTSService` only when you select at least one important `voiceover_text` to implement\n"
                        + "- Constants and shared helper functions if needed; include `VOICEOVER_SPEED` only when voiceover is implemented\n"
                        + "- `class MainScene(VoiceoverScene):` when at least one selected action will use `self.voiceover(...)`; otherwise use `Scene` or `ThreeDScene` if needed\n"
                        + "- When voiceover is implemented, `construct()` must call `self.set_speech_service(GTTSService(lang=\"zh-CN\", global_speed=VOICEOVER_SPEED))` before the first scene method call\n"
                        + "- `def construct(self):` that initializes `self.objects = {}` before calling these scene methods in order: %s\n"
                        + "- A single indented placeholder line `# __SCENE_METHODS__` inside `MainScene`, after `construct()`\n\n"
                        + "Do NOT implement the scene methods yet and do not create `pass` stubs for them.\n"
                        + "The workflow will insert generated methods at `# __SCENE_METHODS__`.\n"
                        + "Return the skeleton code via the write_code_skeleton tool.",
                storyboardJson, methodList));
    }

    /**
     * Builds the user prompt for generating a single scene method body.
     */
    public static String manimSceneCodeUserPrompt(String sceneJson,
                                                  String methodName,
                                                  int sceneIndex,
                                                  int totalScenes) {
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Now implement scene method `%s` (scene %d of %d).\n\n"
                        + "Scene specification:\n```json\n%s\n```\n\n"
                        + "Generate ONLY the method body for `def %s(self):`.\n"
                        + "- Do not include the `def %s(self):` signature; return only the indented or unindented body statements.\n"
                        + "- If no implementation is possible, return `pass` as the body.\n"
                        + "- Use storyboard objects established in earlier scene methods via `self.objects[\"id\"]`; initialize `self.objects` before storing the first object if it does not exist.\n"
                        + "- The current request may include a Runtime object state block. Every id listed there has already been created in an earlier scene method; reuse `self.objects[\"id\"]` for those ids instead of constructing a replacement mobject.\n"
                        + "- For currently invisible but already created ids that re-enter this scene, retrieve the existing mobject, update its placement/style as needed, add it back to the scene, and animate it in. Do not recreate the object under the same storyboard id.\n"
                        + "- When an object exits, remove it from the scene visually but keep its `self.objects[\"id\"]` entry available for later re-entry.\n"
                        + "- Never retrieve semantic objects with `self.mobjects[index]`, `self.mobjects[-1]`, creation order, or list position. `self.mobjects` is allowed only for whole-scene cleanup such as `Group(*self.mobjects)`.\n"
                        + "- Follow the storyboard's semantic intent; select from storyboard-declared objects, and preserve necessary lifecycle and continuity without rendering every candidate object mechanically.\n"
                        + "- Do not create learner-visible elements outside the storyboard. Backend-only invisible helpers are allowed only when required for calculation or documented API usage.\n"
                        + "- Treat `notes_for_codegen` as mandatory for this scene: implement concrete ranges, endpoints, durations, visibility/lifecycle instructions, transforms, palette notes, and layout constraints exactly when present.\n"
                        + "- Treat structured `constraints` as hard invariants. The 'Constraint summary' block in the current request lists per-object constraints for this scene. You MUST respect them:\n"
                        + "  * `lies_on` / `point_on_object`: the point must stay on the referenced object at all times; instantiate it as a point-on-line/curve, not as a free coordinate.\n"
                        + "  * `moves_on_object` with `range`: the point may slide along the referenced object but must NEVER leave it or exceed the specified range; clamp or parameterize the motion accordingly.\n"
                        + "  * `on_side_of`: the object must stay on the declared side of the reference line/boundary; do not construct it on the boundary unless another constraint says it lies there.\n"
                        + "  * `same_side_of`: the objects must stay on the same specified side of the line.\n"
                        + "  * `opposite_side_of`: the objects must stay on opposite sides of the line.\n"
                        + "  * `reflection_across`, `midpoint_of`, `intersection_of`, `projection_onto`, `connects_points`, `line_through_points`, `ray_from_to`, `angle_between`, `right_angle_at`, `arc_sweep`: compute the object from its constraint refs; never hardcode placement coordinates that ignore the dependency.\n"
                        + "  * `angle_between` / `right_angle_at` / `arc_sweep`: preserve the declared marker/arc, vertex or center/anchor, ordered boundary refs, sector, direction, and side. Choose a documented Manim API form that keeps those semantics; do not prefer a two-line, three-point, or hand-drawn implementation when it drops any declared ref or sector.\n"
                        + "- For any object with structured constraints that define derived geometry, measurements, or dependency-driven connectors, compute it from constraint refs or use native Manim/API geometry helpers. For non-derived constrained objects, use scene placement as the preferred initial visual state while preserving the constraint; adjust it if needed for safe layout, readability, or consistency.\n"
                        + "- Respect storyboard text semantics strictly: `kind=equation` means `MathTex(...)`; `kind=text` and `kind=text_card` mean `Text(...)` unless the content clearly requires math rendering; avoid `Tex(...)` unless the scene explicitly needs non-math LaTeX text.\n"
                        + "- Render concise mathematical labels exactly as written in storyboard content, e.g. `B′`, `l`, or `AB`; do not expand labels into explanatory on-screen prose.\n"
                        + "- Render Chinese prose with `Text(..., font=\"Microsoft YaHei\")`; keep formulas in `MathTex(...)`.\n"
                        + "- If an action has `voiceover_text`, implement it only when it is one of the scene's important narration beats: major setup, conceptual turn, non-obvious construction, important reveal, comparison, or conclusion. Omit routine narration for simple entries, exits, waits, styling, obvious moves, or labels.\n"
                        + "- For selected narrated actions, wrap the corresponding visible beat in `with self.voiceover(text=...) as tracker:` and synchronize animation or wait duration with `tracker.duration`.\n"
                        + "- Preserve selected Chinese `voiceover_text` and Chinese visible object content exactly unless shortening is required for readability.\n"
                        + "- Return the method body via the write_scene_code tool.",
                methodName, sceneIndex + 1, totalScenes,
                sceneJson, methodName, methodName));
    }

    /**
     * Builds the user prompt for generating the GeoGebra code skeleton
     * (global setup commands, shared definitions, coordinate system).
     */
    public static String geoGebraSkeletonUserPrompt(String storyboardJson,
                                                     List<String> sceneSectionNames) {
        String sectionList = String.join(", ", sceneSectionNames);
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Generate ONLY the GeoGebra code skeleton (setup section):\n"
                        + "- Global coordinate and view settings, including `SetCoordSystem(-7, 7, -4, 4)` unless already provided downstream\n"
                        + "- Shared base objects that persist across multiple scenes\n"
                        + "- A section comment header for each scene: %s\n\n"
                        + "Do NOT implement the scene-specific objects yet - just provide the global setup.\n"
                        + "Return the skeleton code via the write_code_skeleton tool.",
                storyboardJson, sectionList));
    }

    /**
     * Builds the user prompt for generating a single GeoGebra scene section.
     */
    public static String geoGebraSceneCodeUserPrompt(String sceneJson,
                                                      String sceneSectionName,
                                                      int sceneIndex,
                                                      int totalScenes) {
        return SystemPrompts.buildCurrentRequestSection(String.format(
                "Now implement scene section \"%s\" (scene %d of %d).\n\n"
                        + "Scene specification:\n```json\n%s\n```\n\n"
                        + "Generate the COMPLETE GeoGebra command block for this scene:\n"
                        + "- Start with a comment line: # %s\n"
                        + "- Create only storyboard-declared visible objects needed for this scene; merge or omit non-essential helpers, duplicate labels, and decorative elements when they do not improve teaching clarity\n"
                        + "- Do not create learner-visible elements outside the storyboard. Backend-only invisible helpers are allowed only when required for calculation or documented command usage.\n"
                        + "- Treat `notes_for_codegen` as mandatory for this scene: implement concrete ranges, endpoints, visibility/lifecycle instructions, styles, and layout constraints exactly when present\n"
                        + "- Treat structured `constraints` as hard invariants. The 'Constraint summary' block after the object registry lists per-object constraints for this scene. You MUST respect them:\n"
                        + "  * `lies_on` / `point_on_object`: construct the point with documented GeoGebra path syntax such as `Point(path)` or `PointIn(region)`, never as a free coordinate and never with undocumented `PointOn` syntax.\n"
                        + "  * `moves_on_object` with `range`: create a bounded path when needed, then use `Point(path)` for a directly draggable point constrained to that range; do not use `Point(path, numericConstant)` because that creates a non-draggable dependent point.\n"
                        + "  * `on_side_of`: the object must stay on the declared side of the reference line/boundary; do not construct it on the boundary unless another constraint says it lies there.\n"
                        + "  * `same_side_of`: the objects must stay on the same specified side of the line.\n"
                        + "  * `opposite_side_of`: the objects must stay on opposite sides of the line.\n"
                        + "  * `reflection_across`, `midpoint`, `projection`, `connects_points`, `angle_between`, `right_angle_at`, `arc_sweep`: construct the object from its dependency objects with native GeoGebra commands; never hardcode placement coordinates that ignore the dependency.\n"
                        + "  * `angle_between` / `right_angle_at` / `arc_sweep`: preserve the declared marker/arc, vertex or center/anchor, ordered boundary refs, sector, direction, and side. Choose a documented GeoGebra form that keeps those semantics; do not prefer line-based, vector-based, three-point, or helper-object syntax when it drops any declared ref or sector.\n"
                        + "- For derived objects such as intersections, reflections, midpoints, projections, connecting segments, and angle markers, construct them from their dependency objects with native GeoGebra commands instead of hardcoded placement coordinates\n"
                        + "- Apply styles and visibility settings\n"
                        + "- Preserve Chinese learner-facing visible text from storyboard object content; use English GeoGebra command names and ASCII backend identifiers.\n"
                        + "- Reference shared objects from the skeleton by their established names\n"
                        + "- The current request may include a Runtime object state block. Every id listed there has already been constructed in an earlier scene section; update/reuse those names instead of defining replacement objects under the same storyboard id.\n"
                        + "- Return the scene code via the write_scene_code tool.",
                sceneSectionName, sceneIndex + 1, totalScenes,
                sceneJson, sceneSectionName));
    }
}
