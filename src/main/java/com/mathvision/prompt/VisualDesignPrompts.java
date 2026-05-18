package com.mathvision.prompt;

/**
 * Prompts for Stage 1b: visual design (scene-level output).
 *
 * Split into two parts:
 * - buildRulesPrompt(): hard rules (visual design rules, output schema, backend rules)
 * - buildFixedContextPrompt(): workflow prefix + solution chain + backend style reference
 *
 * Each knowledge node produces a full StoryboardScene plus new_objects
 * for the global object registry.
 */
public final class VisualDesignPrompts {

    private static final String SCENE_OUTPUT_FORMAT =
            "Output format:\n"
                    + StoryboardSchemaPrompts.JSON_SYNTAX_REQUIREMENTS
                    + "Return a JSON object with two top-level keys: `scene` and `new_objects`.\n"
                    + "`scene.entering_objects` and `scene.persistent_objects` are scene-level patches: each entry carries only `id` plus optional `placement` and `style`. Do NOT include kind, content, source_node, behavior, anchor_id, dependency_objects, dependency_relation, or constraints here — those belong in `new_objects`.\n"
                    + "`new_objects` entries represent the canonical registry definition of each object introduced in this scene. They carry identity, content, dependency, and behavior but not scene-specific placement or style.\n"
                    + "Only add `new_objects` that are teaching-essential, clarify the current beat, or carry distinct geometry/dependency semantics. Required labels or callouts with their own attachment behavior are not duplicates. Reuse existing registry ids instead of creating repeated formulas, redundant highlights, or decorative helper objects.\n"
                    + "{\n"
                    + "  \"scene\": {\n"
                    + StoryboardSchemaPrompts.SCENE_FIELDS_SCHEMA
                    + "\n  },\n"
                    + "  \"new_objects\": [\n"
                    + StoryboardSchemaPrompts.OBJECT_DEFINITION_SCHEMA
                    + "\n  ]\n"
                    + "}\n\n";

    private static final String SCENE_EXAMPLE_OUTPUT =
            "Example output:\n"
                    + StoryboardSchemaPrompts.JSON_LEXICAL_EXAMPLES
                    + "Scene 1 example (first scene — persistent_objects and exiting_objects must be empty):\n"
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
                    + "\n  ]\n"
                    + "}\n\n"
                    + "Scene 2 example (continuation scene — persistent_objects carry ids from earlier scenes):\n"
                    + "{\n"
                    + "  \"scene\": {\n"
                    + StoryboardSchemaPrompts.EXAMPLE_SCENE2_BODY
                    + "\n  },\n"
                    + "  \"new_objects\": [\n"
                    + StoryboardSchemaPrompts.EXAMPLE_MIN_MARKER
                    + "\n  ]\n"
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
                    + "- Write narration as learner-facing beats: each sentence should correspond to something visible, moved, manipulated, highlighted, transformed, or deliberately held on screen.\n"
                    + "- Leave breathing room after key reveals; do not imply nonstop motion with no time to read.\n"
                    + "- Plan scene transitions intentionally: choose clean break (fade all, pause), carry-forward (keep one anchor, fade rest), or transform bridge for each scene boundary. Record the chosen style in `notes_for_codegen` only when downstream code generation must preserve it as a hard constraint.\n"
                    + "- When the current step merges multiple prerequisite branches, treat the scene as a convergence beat: inherit existing object names, color meanings, and continuity anchors instead of restarting the story.\n"
                    + "- For merge scenes, combine upstream conclusions into one coherent scene and do not replay each branch as if it were brand new.\n"
                    + "- Keep the storyboard lean: prefer moving, transforming, dragging, sweeping, or restyling existing elements over adding explanatory text or new objects when they communicate the same idea.\n"
                    + "- When a temporary element has served its purpose, include it in `exiting_objects` of the current or next scene.\n"
                    + "- Use enrichment fields only when they sharpen the explanation.\n"
                    + "- Narrative must not be constrained by a fixed word count.\n"
                    + "- Duration estimation reference: title card 3-5s, concept introduction 10-20s, equation reveal 15-25s, algorithm step 5-10s, aha-moment beat 15-30s, conclusion 5-10s. Use these ranges when setting `duration_seconds`.\n"
                    + "- Keep object ids concise and non-redundant since `kind` already carries the type. Good ids: `AB`, `P`, `l`; bad ids: `segmentAB`, `LineAB`, `PointP`. Follow only the naming rules for the active backend.\n"
                    + "- Reuse the exact same concise ids consistently in `anchor_id`, `persistent_objects`, `exiting_objects`, and `actions.targets`.\n"
                    + "- When any field inside `entering_objects` refers to another object, especially `content`, refer to that object by id only. Do not restate its kind there.\n"
                    + "- For example, write `angle between AP and l at P`, not `angle between segment AP and line l at point P`.\n";

    private static final String SCENE_STYLE_LAYOUT_RULES =
            "Scene style and layout rules:\n"
                    + "- Design placement, style, color, and visual hierarchy now; downstream validation may clean up the whole storyboard, but this node must produce a strong first-pass scene layout.\n"
                    + "- Plan where the eye should look first, what remains as dim context, and what area stays open for overlays or later reveals.\n"
                    + "- Place formulas near edges, not over the main geometry.\n"
                    + "- Use the provided object registry, used colors, and style history to preserve meaning across scenes.\n"
                    + "- Once a color is assigned to a concept, it keeps that meaning across the entire storyboard. Record non-obvious color-to-concept assignments in `notes_for_codegen` only when they must be enforced downstream.\n"
                    + "- Assign colors to concepts, not to individual objects. Once a color is assigned to a concept, it keeps that meaning across the entire presentation.\n"
                    + "- Plan per-scene variation: vary the dominant color, spatial layout, animation entry style, and visual density across scenes. Never use identical visual config for every scene.\n"
                    + "- Use a single typed `style` object per storyboard object, never a style array and never custom style keys.\n"
                    + "- Style describes the object itself only. Create separate storyboard objects for labels, badges, helper outlines, cards, or callouts that have their own identity.\n"
                    + "- Prefer `kind = text` or `kind = equation` over `kind = text_card` or `kind = formula_card`. Display text directly without a background box/card unless the card itself is teaching-essential (e.g. a titled result panel). Most formulas and labels are clearer without a surrounding box.\n"
                    + "- Objects whose coordinates can be computed from other elements (e.g. intersections, midpoints, perpendicular feet) do not need `placement`; only objects that require an initial or free position (e.g. a moving point on a line) should carry `placement`.\n"
                    + "- Only include `style` when it adds meaningful rendering properties; omit it for visually plain objects.\n";

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
                    + "- If a point, marker, label, counter, or helper must visibly follow another object, create a separate object and describe the attachment with `behavior`, `anchor_id`, `dependency_objects`, and `dependency_relation`.\n"
                    + "- For moving points or markers, create a separate label object with `behavior = follows_anchor` so the label tracks the moving object.\n"
                    + "- Manim does not auto-label objects. When an object's name or value helps the learner understand the current beat, explicitly declare a companion `kind: text` label in the same scene's `entering_objects`; attach it with `behavior = follows_anchor` and `anchor_id` pointing to the parent object's id. Omit labels that are redundant or do not improve understanding.\n"
                    + "- Do not use `style.label_visible` to request a visible label; labels must be explicit `kind: text` or `kind: equation` companion objects with `dependency_relation = label_for`.\n"
                    + StoryboardSchemaPrompts.MANIM_COMPANION_LABEL_EXAMPLE
                    + "- Use `screen_overlay_plan` only for true viewport-fixed explanatory overlays, not as a vague place to hide layout conflicts.\n"
                    + SCENE_STYLE_LAYOUT_RULES
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + SystemPrompts.MANIM_COLOR_RULES_BULLETS
                    + "Manim visual-planning constraints:\n"
                    + "- " + SystemPrompts.MANIM_LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed for the teaching goal.\n"
                    + "- Use the default black background `#000000` and choose readable 6-digit hex foreground colors by contrast, not by a fixed color whitelist.\n"
                    + "- Prefer a stable world layout and meaningful transforms over repeatedly replacing the whole diagram.\n"
                    + "- Distinguish what should animate from what should stay static; favor motion for meaning-carrying elements and avoid decorative motion that adds load.\n"
                    + "- The visual plan must be concrete enough for documented Manim constructs, with no hidden assumptions.\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SCENE_TEACHING_RULES
                    + SystemPrompts.MANIM_NAMING_RULES + "\n"
                    + SystemPrompts.ASCII_TEXT_RULES
                    + StoryboardSchemaPrompts.JSON_LEXICAL_CONTRACT
                    + SCENE_OUTPUT_FORMAT
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
                    + "- If the visible text is just the object's own name or symbol, keep it as the object's native label rather than creating a separate `label` or `text` storyboard object.\n"
                    + "- Create separate `label` or `text` objects only for overlays, formulas, counters, captions, explanatory annotations, or text that is semantically different from the object's native label. Avoid redundant pairs such as `A` plus `aLabel`, `lineL` plus `labelL`, or `circleO` plus `labelO`.\n"
                    + "- Use `fixed_overlay` mainly for explanatory text, counters, captions, formulas, and similar viewport-fixed overlays. For geometric points, lines, circles, angle markers, and bullseye-style highlights that belong to the construction, prefer `static` or `derived` unless the object is truly an overlay.\n"
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n"
                    + "- Do not mention specific GeoGebra command names in storyboard notes unless they are documented in the active syntax manual; describe unsupported effects generically instead.\n"
                    + SCENE_STYLE_LAYOUT_RULES
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS + "\n"
                    + SystemPrompts.GEOGEBRA_COLOR_RULES_BULLETS
                    + "Visual design principles:\n"
                    + "- Prefer direct visual reasoning with draggable, constrained, or movable construction elements over text-heavy explanation.\n"
                    + "- Keep the learner oriented around one stable construction when possible.\n"
                    + "- Let formulas support the visual argument instead of replacing it.\n"
                    + "- If a reasoning step is not naturally visible, design a faithful construction-based proxy.\n"
                    + "GeoGebra planning constraints:\n"
                    + "- " + SystemPrompts.LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + SystemPrompts.GEOGEBRA_VIEWPORT_RULES
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed.\n"
                    + "- Keep the visual plan implementable without hidden assumptions.\n"
                    + "- Prefer readable construction layout and clear label placement over animation-like staging language.\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SCENE_TEACHING_RULES
                    + SystemPrompts.GEOGEBRA_NAMING_RULES + "\n"
                    + SystemPrompts.ASCII_TEXT_RULES
                    + StoryboardSchemaPrompts.JSON_LEXICAL_CONTRACT
                    + SCENE_OUTPUT_FORMAT
                    + "\n"
                    + SCENE_EXAMPLE_OUTPUT;

    private VisualDesignPrompts() {}

    /**
     * Returns hard rules for visual design: visual design rules, output schema,
     * backend-specific rules, examples.
     */
    public static String buildRulesPrompt(String outputTarget) {
        return SystemPrompts.buildRulesSection("geogebra".equalsIgnoreCase(outputTarget)
                ? GEOGEBRA_SYSTEM
                : MANIM_SYSTEM);
    }

    /**
     * Returns fixed background context: workflow prefix + backend intro +
     * solution chain + backend style reference.
     */
    public static String buildFixedContextPrompt(String targetConcept,
                                                  String targetDescription,
                                                  String outputTarget,
                                                  String solutionChain) {
        StringBuilder sb = new StringBuilder();
        sb.append(SystemPrompts.buildWorkflowPrefix(
                "Stage 1b / Visual Design",
                "Scene visual design",
                targetConcept,
                targetDescription,
                outputTarget));
        sb.append("geogebra".equalsIgnoreCase(outputTarget)
                ? "Design for GeoGebra as an interactive construction medium.\n\n"
                : "Design for Manim as a teaching animation medium rather than a backend-neutral compromise.\n\n");
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
}
