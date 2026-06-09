package com.mathvision.prompt;

import com.mathvision.model.ProblemBundle;
import com.mathvision.model.ProblemDiagram;
import com.mathvision.util.JsonUtils;
import com.mathvision.util.ProblemBundleContextBuilder;
import com.mathvision.util.SceneModeUtils;

/**
 * Prompts for Stage 3: visual design (scene-level output).
 *
 * Split into two parts:
 * - buildRulesPrompt(): hard rules (visual design rules, output schema, backend rules)
 * - buildFixedContextPrompt(): workflow prefix + solution chain + backend style reference
 *
 * Each knowledge node produces a full StoryboardScene plus new_objects
 * for the global object registry.
 */
public final class VisualDesignPrompts {

    private static String sceneOutputFormat(String outputTarget) {
        return sceneOutputFormat(outputTarget, SceneModeUtils.MODE_2D);
    }

    private static String sceneOutputFormat(String outputTarget, String sceneMode) {
        return "Output format:\n"
                + StoryboardSchemaPrompts.JSON_SYNTAX_REQUIREMENTS
                + "Return a JSON object with top-level keys `scene`, `new_objects`, and `coordinate_bounds_update`.\n"
                + "Also return `coordinate_bounds_update`, the storyboard world-coordinate extrema introduced or required by this scene before final padding is applied. Relative placement offsets are resolved from their anchors before they contribute to this range.\n"
                + "`scene.entering_objects` and `scene.persistent_objects` are scene-level patches: each entry carries only `id` plus optional `placement` and `style`. Do NOT include kind, content, or constraints here - those belong in `new_objects`.\n"
                + "`new_objects` entries represent the canonical registry definition of each object introduced in this scene. They carry identity, content, style, and structured constraints but not scene-specific placement.\n"
                + "Only add `new_objects` that are teaching-essential, clarify the current beat, or carry distinct geometry/constraint semantics. Required Manim labels or callouts with their own attachment constraint are not duplicates. Reuse existing registry ids instead of creating repeated formulas, redundant highlights, or decorative helper objects.\n"
                + "{\n"
                + "  \"scene\": {\n"
                + StoryboardSchemaPrompts.sceneFieldsSchema(outputTarget, sceneMode)
                + "\n  },\n"
                + "  \"new_objects\": [\n"
                + StoryboardSchemaPrompts.OBJECT_DEFINITION_SCHEMA
                + "\n  ],\n"
                + StoryboardSchemaPrompts.coordinateBoundsUpdateSchema(sceneMode) + "\n"
                + "}\n\n";
    }

    private static final String SCENE_EXAMPLE_OUTPUT =
            "Example output:\n"
                    + StoryboardSchemaPrompts.JSON_LEXICAL_EXAMPLES
                    + "Scene 1 example (first scene - persistent_objects and exiting_objects must be empty):\n"
                    + "{\n"
                    + "  \"scene\": {\n"
                    + StoryboardSchemaPrompts.EXAMPLE_SCENE1_BODY
                    + "\n  },\n"
                    + "  \"new_objects\": [\n"
                    + StoryboardSchemaPrompts.EXAMPLE_NUMBER_LINE
                    + ",\n"
                    + StoryboardSchemaPrompts.EXAMPLE_POINT_P
                    + ",\n"
                    + StoryboardSchemaPrompts.EXAMPLE_FORMULA_CARD
                    + "\n  ],\n"
                    + "  \"coordinate_bounds_update\": {\n"
                    + "    \"x\": { \"min\": -3, \"max\": 3 },\n"
                    + "    \"y\": { \"min\": 0, \"max\": 2 },\n"
                    + "    \"reason\": \"Raw resolved scene placements for numberLine, P, and formulaCard before final padding.\"\n"
                    + "  }\n"
                    + "}\n\n"
                    + "Scene 2 example (continuation scene - persistent_objects carry ids from earlier scenes):\n"
                    + "{\n"
                    + "  \"scene\": {\n"
                    + StoryboardSchemaPrompts.EXAMPLE_SCENE2_BODY
                    + "\n  },\n"
                    + "  \"new_objects\": [\n"
                    + StoryboardSchemaPrompts.EXAMPLE_MIN_MARKER
                    + "\n  ],\n"
                    + "  \"coordinate_bounds_update\": {\n"
                    + "    \"x\": { \"min\": 1, \"max\": 1 },\n"
                    + "    \"y\": { \"min\": 2, \"max\": 2 },\n"
                    + "    \"reason\": \"Raw resolved scene placement for minMarker before final padding.\"\n"
                    + "  }\n"
                    + "}\n\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT + " Do not wrap it in markdown.";

    private static final String SCENE_AUTHORING_RULES =
            SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_OBJECT_SEMANTICS
                        .replace("\n- ", "\n- ")
                        .replace("How to interpret the storyboard fields:\n", "Field responsibilities: ").trim() + "\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_SCENE_STRUCTURE.trim() + "\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_SCENE_LAYOUT.trim() + "\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_AUTHORING_RULES
                    + SystemPrompts.GEOMETRIC_MARKER_AUTHORING_RULES;

    private static final String SCENE_TEACHING_RULES =
            "Scene teaching rules:\n"
                    + "- Write action descriptions as learner-facing visual beats: each action should correspond to something visible, moved, manipulated, highlighted, transformed, or deliberately held on screen.\n"
                    + "- Add narrated action text sparingly, only to key actions that carry the main teaching point, a non-obvious construction, a major reveal, a comparison, or a conclusion.\n"
                    + "- Do not narrate an action merely because it is visible; routine entries, exits, waits, simple moves, style changes, and obvious label/formula appearances should usually stay silent.\n"
                    + "- A typical scene should contain 0-2 narrated actions, not narration on every action.\n"
                    + "- Leave breathing room after key reveals; do not imply nonstop motion with no time to read.\n"
                    + "- Plan scene transitions intentionally by judging each visible object's future teaching utility: persist, dim, transform, or exit it based on whether it will help the next scene's reasoning. Record lifecycle decisions in `notes_for_codegen` only when downstream code generation must preserve them as hard constraints.\n"
                    + "- When the current step merges multiple prerequisite branches, treat the scene as a convergence beat: inherit useful object names, color meanings, and continuity anchors without replaying each branch as if it were brand new.\n"
                    + "- For merge scenes, combine upstream conclusions into one coherent scene and decide which branch evidence should remain as support, which should be dimmed, and which has finished its role.\n"
                    + "- Keep the storyboard lean through judgment, not blanket removal: prefer moving, transforming, dragging, sweeping, or restyling existing elements over adding explanatory text; dim or exit objects when they have finished their role.\n"
                    + "- Include an object in `exiting_objects` when its role is complete, it will not support upcoming reasoning, or keeping it would distract from the next idea; keep or dim it when it remains useful as evidence, an anchor, a comparison target, or learner orientation.\n"
                    + "- Use enrichment fields only when they sharpen the explanation.\n"
                    + "- Narrative must not be constrained by a fixed word count.\n"
                    + "- Duration estimation reference: title card 3-5s, concept introduction 10-20s, equation reveal 15-25s, algorithm step 5-10s, aha-moment beat 15-30s, conclusion 5-10s. Use these ranges when setting `duration_seconds`.\n"
                    + "- Keep object ids concise and non-redundant since `kind` already carries the type. Good ids: `AB`, `P`, `l`; bad ids: `segmentAB`, `LineAB`, `PointP`. Follow only the naming rules for the active backend.\n"
                    + "- Reuse the exact same concise ids consistently in `persistent_objects`, `exiting_objects`, and `actions.targets`; express geometry/attachment relationships through structured `constraints`.\n"
                    + "- When `new_objects[].content` is visible on screen, keep it terse and mathematically named. For `kind=text` labels attached to geometry, write only the label itself (e.g. `B′`, `l`, `AB`, `P_min`), not a descriptive phrase such as `反射点B′`, `直线l`, or `线段AB`.\n"
                    + "- Put explanatory prose in `goal`, `narration`, or action `description`, not in object label content.\n"
                    + "- When `new_objects[].content` or constraint refs mention another object, refer to that object by id only. Do not restate its kind there.\n"
                    + "- For example, write registry content as `angle between AP and l at P`, not `angle between segment AP and line l at point P`.\n";

    private static final String SCENE_STYLE_LAYOUT_RULES =
            sceneStyleLayoutRules(SceneModeUtils.MODE_2D);

    private static String sceneStyleLayoutRules(String sceneMode) {
        String axes = SceneModeUtils.isThreeD(sceneMode) ? "x/y/z" : "x/y";
        String zRule = SceneModeUtils.isThreeD(sceneMode)
                ? "- This problem is 3D: use `placement.z` and `coordinate_bounds_update.z` only for mathematical depth coordinates; keep `style.z_index` for draw/layer order.\n"
                : "- This problem is 2D: do not output `placement.z` or `coordinate_bounds_update.z`; use only `style.z_index` for draw/layer order.\n";
        return
            "Scene style and layout rules:\n"
                    + "- Design placement, style, color, and visual hierarchy now; downstream validation may clean up the whole storyboard, but this node must produce a strong first-pass scene layout.\n"
                    + "- Treat placement numbers as storyboard world coordinates, not backend render-frame positions. Record each scene's resolved storyboard extrema in `coordinate_bounds_update` so later code can map them into the target backend.\n"
                    + "- Use `placement.positioning = absolute` for direct storyboard coordinates and `relative` for offsets from an anchor object. For relative placements, " + axes + " are offsets, not a separate coordinate system.\n"
                    + "- Make the update range cover every explicit " + axes + " value or min/max used by visible resolved placements. The final storyboard will add padding where applicable; do not rely on an element exactly touching a coordinate bound.\n"
                    + zRule
                    + "- Use the provided object registry, used colors, and style history to preserve meaning across scenes.\n"
                    + "- Record non-obvious palette, transition, or layout decisions in `notes_for_codegen` only when downstream code generation must preserve them as hard constraints.\n"
                    + "- Plan per-scene variation: vary the dominant color, spatial layout, animation entry style, and visual density across scenes. Never use identical visual config for every scene.\n"
                    + "- Objects owned by coordinate-derived relations (e.g. intersections, midpoints, perpendicular feet, attached labels) must not carry scene `placement`; express their position through structured constraint refs and parameters instead.\n"
                    + "- Objects owned only by non-coordinate-derived relations such as `point_at`, `lies_on`, `on_side_of`, `moves_on_object`, `fixed_overlay`, or `distance_between` may still carry `placement` when they need an initial, free, or screen-fixed position.\n"
                    + "- Only include `style` when it adds meaningful rendering properties; omit it for visually plain objects.\n";
    }

    private static final String MANIM_SYSTEM =
            "You are a Manim-first visual designer for math teaching visualizations.\n"
                    + "You are designing ONE scene at a time for a sequential storyboard. Each knowledge-graph node becomes one scene.\n"
                    + "Use the conversation history and object registry to maintain visual continuity with previous scenes.\n"
                    + "Turn abstract reasoning into a learner-facing visual plan before any code is written.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + SCENE_AUTHORING_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_AUTHORING_RULES
                    + "Manim object and label rules:\n"
                    + "- Every planned teaching-essential Manim object must be explicitly represented in `entering_objects` or `persistent_objects`; avoid adding temporary decoration, duplicate labels, or unhelpful helper objects to the storyboard.\n"
                    + "- For named teaching-essential points, lines, intersections, and other geometry whose id or value must be read by the learner, the visible label is mandatory unless the current beat explicitly hides it.\n"
                    + "- If a point, marker, label, counter, or helper must visibly follow another object, create a separate object and describe the attachment with a structured `attachment/label_for`, `attachment/anchored_to`, or `attachment/fixed_offset_from` constraint.\n"
                    + "- For moving points or markers in Manim, create a separate label object only when the label is teaching-essential; attach it with `relation = label_for` and refs `{label, anchor}` so downstream code can keep it synchronized.\n"
                    + "- Manim does not auto-label objects. When an object's name or value helps the learner understand the current beat, explicitly declare a companion `kind: text` or `kind: equation` label in `new_objects`, include it in the scene patch, and attach it through an `attachment/label_for` constraint. Omit labels that are redundant or do not improve understanding.\n"
                    + "- Do not use `style.label_visible` to request a visible Manim label; labels must be explicit companion objects with attachment constraints.\n"
                    + StoryboardSchemaPrompts.MANIM_COMPANION_LABEL_EXAMPLE
                    + "- Use `screen_overlay_plan` only for true viewport-fixed explanatory overlays, not as a vague place to hide layout conflicts.\n"
                    + SCENE_STYLE_LAYOUT_RULES
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.COLOR_FORMAT_RULES_BULLETS
                    + SystemPrompts.MANIM_COLOR_RULES_BULLETS
                    + "Manim visual-planning constraints:\n"
                    + "- " + SystemPrompts.MANIM_LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Prefer a stable world layout and meaningful transforms over repeatedly replacing the whole diagram.\n"
                    + "- Distinguish what should animate from what should stay static; favor motion for meaning-carrying elements and avoid decorative motion that adds load.\n"
                    + "- The visual plan must be concrete enough for documented Manim constructs, with no hidden assumptions.\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SCENE_TEACHING_RULES
                    + SystemPrompts.MANIM_NAMING_RULES + "\n"
                    + SystemPrompts.ASCII_TEXT_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + SystemPrompts.MANIM_VOICEOVER_RULES
                    + SystemPrompts.MANIM_CHINESE_TEXT_RENDERING_RULES
                    + StoryboardSchemaPrompts.JSON_LEXICAL_CONTRACT
                    + sceneOutputFormat("manim")
                    + "\n"
                    + SCENE_EXAMPLE_OUTPUT;

    private static final String GEOGEBRA_SYSTEM =
            "You are a visual designer for GeoGebra teaching constructions.\n"
                    + "You are designing ONE scene at a time for a sequential storyboard. Each knowledge-graph node becomes one scene.\n"
                    + "Use the conversation history and object registry to maintain visual continuity with previous scenes.\n"
                    + "Turn abstract reasoning into something the learner can first see through a manipulable or constrained construction, then compare or explain with supporting text.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + SCENE_AUTHORING_RULES
                    + SystemPrompts.MINIMIZE_HELPER_OBJECTS_AUTHORING_RULES
                    + "GeoGebra label and object rules:\n"
                    + "- Follow GeoGebra naming conventions.\n"
                    + "- Prefer native GeoGebra labels for named geometric objects such as points, lines, segments, rays, circles, and polygons.\n"
                    + "- If the visible text is just the object's own name or symbol, keep it as the object's native label rather than creating a separate storyboard object. GeoGebra mode must not create independent label companion objects for geometry names.\n"
                    + "- Create separate `text` or `equation` objects only for overlays, formulas, counters, captions, explanatory annotations, or text that is semantically different from the object's native label. Avoid redundant pairs such as `A` plus `aLabel`, `lineL` plus `labelL`, or `circleO` plus `labelO`.\n"
                    + "- Use `attachment/fixed_overlay` constraints mainly for explanatory text, counters, captions, formulas, and similar viewport-fixed overlays. For geometric points, lines, circles, angle markers, and bullseye-style highlights that belong to the construction, use construction/constraint/motion/marker constraints instead of overlay semantics.\n"
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n"
                    + "- Do not mention specific GeoGebra command names in storyboard notes unless they are documented in the active syntax manual; describe unsupported effects generically instead.\n"
                    + SCENE_STYLE_LAYOUT_RULES
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.COLOR_FORMAT_RULES_BULLETS
                    + SystemPrompts.GEOGEBRA_COLOR_RULES_BULLETS
                    + "Visual design principles:\n"
                    + "- Prefer direct visual reasoning with draggable, constrained, or movable construction elements over text-heavy explanation.\n"
                    + "- Keep the learner oriented around one stable construction when possible.\n"
                    + "- Let formulas support the visual argument instead of replacing it.\n"
                    + "- If a reasoning step is not naturally visible, design a faithful construction-based proxy.\n"
                    + "GeoGebra planning constraints:\n"
                    + SystemPrompts.GEOGEBRA_VIEWPORT_RULES
                    + "- Keep the visual plan implementable without hidden assumptions.\n"
                    + "- Prefer readable construction layout and clear label placement over animation-like staging language.\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SCENE_TEACHING_RULES
                    + SystemPrompts.GEOGEBRA_NAMING_RULES + "\n"
                    + SystemPrompts.ASCII_TEXT_RULES
                    + SystemPrompts.VISIBLE_CHINESE_TEXT_RULES
                    + StoryboardSchemaPrompts.JSON_LEXICAL_CONTRACT
                    + sceneOutputFormat("geogebra")
                    + "\n"
                    + SCENE_EXAMPLE_OUTPUT;

    private VisualDesignPrompts() {}

    /**
     * Returns hard rules for visual design: visual design rules, output schema,
     * backend-specific rules, examples.
     */
    public static String buildRulesPrompt(String outputTarget) {
        return buildRulesPrompt(outputTarget, SceneModeUtils.MODE_2D);
    }

    public static String buildRulesPrompt(String outputTarget, String sceneMode) {
        String normalizedSceneMode = SceneModeUtils.normalize(sceneMode);
        String system = "geogebra".equalsIgnoreCase(outputTarget)
                ? GEOGEBRA_SYSTEM
                : MANIM_SYSTEM;
        system = system
                .replace(SCENE_STYLE_LAYOUT_RULES, sceneStyleLayoutRules(normalizedSceneMode))
                .replace(sceneOutputFormat(outputTarget), sceneOutputFormat(outputTarget, normalizedSceneMode));
        return SystemPrompts.buildRulesSection(system);
    }

    /**
     * Returns fixed background context: workflow prefix + backend intro +
     * solution chain + backend style reference.
     */
    public static String buildFixedContextPrompt(ProblemBundle problemBundle,
                                                  String targetDescription,
                                                  String outputTarget,
                                                  String solutionChain) {
        return buildFixedContextPrompt(problemBundle, targetDescription, outputTarget, solutionChain, SceneModeUtils.MODE_2D);
    }

    public static String buildFixedContextPrompt(String legacyTargetConcept,
                                                  String targetDescription,
                                                  String outputTarget,
                                                  String solutionChain) {
        return buildFixedContextPrompt(
                ProblemBundleContextBuilder.legacyBundle(legacyTargetConcept),
                targetDescription,
                outputTarget,
                solutionChain);
    }

    public static String buildFixedContextPrompt(ProblemBundle problemBundle,
                                                  String targetDescription,
                                                  String outputTarget,
                                                  String solutionChain,
                                                  String sceneMode) {
        StringBuilder sb = new StringBuilder();
        sb.append(SystemPrompts.buildWorkflowPrefix(
                "Stage 3 / Visual Design",
                "Scene visual design",
                ProblemBundleContextBuilder.displayTitle(problemBundle),
                targetDescription,
                outputTarget));
        sb.append(ProblemBundleContextBuilder.buildProblemBundleAuthorityContext(problemBundle)).append("\n\n");
        appendInitialDiagramContract(sb, problemBundle);
        sb.append("geogebra".equalsIgnoreCase(outputTarget)
                ? "Design for GeoGebra as an interactive construction medium.\n\n"
                : "Design for Manim as a teaching animation medium rather than a backend-neutral compromise.\n\n");
        sb.append("Problem-level scene_mode: `").append(SceneModeUtils.normalize(sceneMode)).append("`. ")
                .append("This is fixed for the whole storyboard and must not be emitted as a scene field.\n\n");
        if (solutionChain != null && !solutionChain.isBlank()) {
            sb.append("\n\n").append(solutionChain);
        }
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            sb.append("\n\n").append(SystemPrompts.ensureGeoGebraStyleReference(""));
        } else {
            sb.append("\n\n").append(SystemPrompts.ensureManimStyleReference(""));
        }
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    private static void appendInitialDiagramContract(StringBuilder sb, ProblemBundle problemBundle) {
        if (problemBundle == null || !problemBundle.hasDiagram()) {
            return;
        }
        ProblemDiagram diagram = problemBundle.getDiagram();
        sb.append("Mandatory initial diagram contract:\n");
        sb.append("- Scene 1 must construct the source-observed initial problem diagram before any solution-specific construction.\n");
        sb.append("- The ProblemBundle diagram payload is native JSON source evidence, not storyboard-ready object definitions. Translate it into `new_objects`, object ids, placements, and structured storyboard constraints.\n");
        sb.append("- Preserve named point/segment/region/mark identities from `diagram_description` when they are present; use concise ids that match mathematical labels such as A, B, C, P, Q, arc_AB, segment_AQ.\n");
        sb.append("- Convert `coordinate_model` coordinates, equations, ranges, and dependency formulas into storyboard constraints and notes_for_codegen where they are needed for reliable rendering.\n");
        sb.append("- Convert source-resolved choices in `ambiguities` into hard storyboard branch constraints. Examples: choose the stated side of a line, clockwise/counterclockwise rotation, near/far intersection, minor/major arc, or inside/outside branch.\n");
        sb.append("- If `unknowns` includes moving or dependent quantities, make Scene 1 or the first relevant scene show the variable/dependent relationship without solving the problem prematurely.\n");
        if (diagram.getDiagramDescription() != null && !diagram.getDiagramDescription().isNull()) {
            sb.append("- Source diagram description JSON:\n")
                    .append(diagram.getDiagramDescription().toPrettyString()).append("\n");
        }
        if (diagram.getCoordinateModel() != null && !diagram.getCoordinateModel().isNull()) {
            sb.append("- Coordinate/model JSON:\n")
                    .append(diagram.getCoordinateModel().toPrettyString()).append("\n");
        }
        if (diagram.getUnknowns() != null && !diagram.getUnknowns().isEmpty()) {
            sb.append("- Unknowns JSON: ").append(JsonUtils.toJson(diagram.getUnknowns())).append("\n");
        }
        if (diagram.getAmbiguities() != null && !diagram.getAmbiguities().isEmpty()) {
            sb.append("- Ambiguities/branch choices JSON: ").append(JsonUtils.toJson(diagram.getAmbiguities())).append("\n");
        }
        if (diagram.getNormalizationNotes() != null && !diagram.getNormalizationNotes().isEmpty()) {
            sb.append("- Normalization notes: ").append(JsonUtils.toJson(diagram.getNormalizationNotes())).append("\n");
        }
        sb.append("- Do not introduce solution-specific auxiliary objects in Scene 1 before the source-observed initial diagram is established.\n\n");
    }
}
