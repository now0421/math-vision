package com.mathvision.prompt;

/**
 * Shared JSON schema fragments for storyboard output formats.
 *
 * Both {@link VisualDesignPrompts} (single-scene, wrapped in
 * {@code { "scene": ..., "new_objects": ... }}) and
 * {@link NarrativePrompts} (full storyboard, wrapped in
 * {@code { "continuity_plan": ..., "global_visual_rules": ..., "object_registry": [...], "scenes": [...] }})
 * use identical field definitions for placement, style, scene-patch objects,
 * object definitions, and scene fields. This class collects those shared
 * fragments so both consumers can assemble their top-level schemas from the
 * same building blocks.
 */
public final class StoryboardSchemaPrompts {

    // ── Lexical rules ──────────────────────────────────────────────────

    /** Strict JSON syntax requirements that apply to every storyboard output. */
    public static final String JSON_SYNTAX_REQUIREMENTS =
            "Strict JSON syntax requirements:\n"
                    + "- Return one JSON object only. No markdown fence and no prose before or after it.\n"
                    + "- Use double quotes for all keys and all string values.\n"
                    + "- Categorical/string fields must be quoted everywhere, including style properties and action metadata.\n"
                    + "- Allowed unquoted literals are only numbers, true, false, and null.\n";

    /** Lexical contract reinforcing quote discipline and forbidding bare identifiers. */
    public static final String JSON_LEXICAL_CONTRACT =
            "JSON lexical contract:\n"
                    + "- Use double quotes for all JSON keys and all string values, including categorical fields such as kind, behavior, scene_mode, action type, style role/type, color names, and label content.\n"
                    + "- Do not output markdown fences, comments, trailing commas, or single-quoted strings.\n"
                    + "- Do not output bare identifiers as JSON values. Invalid: \"type\": create. Valid: \"type\": \"create\".\n";

    /** Invalid vs. valid JSON examples that demonstrate common quoting mistakes. */
    public static final String JSON_LEXICAL_EXAMPLES =
            "Invalid examples to avoid:\n"
                    + "- {\"type\": create}\n"
                    + "- {\"behavior\": static}\n"
                    + "- {\"properties\": {\"color\": YELLOW}}\n"
                    + "Valid equivalents:\n"
                    + "- {\"type\": \"create\"}\n"
                    + "- {\"behavior\": \"static\"}\n"
                    + "- {\"properties\": {\"color\": \"YELLOW\"}}\n";

    // ── Field-level schemas ────────────────────────────────────────────

    /** The placement object schema used in entering_objects and persistent_objects patches. */
    public static final String PLACEMENT_SCHEMA =
            "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"string, one of world|screen|anchor\",\n"
                    + "            \"x\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" },\n"
                    + "            \"y\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" },\n"
                    + "            \"z\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" }\n"
                    + "          }";

    /** The style array schema used in entering_objects and persistent_objects patches. */
    public static final String STYLE_SCHEMA =
            "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"string, visual role such as text|background|border|glow|badge|emphasis\",\n"
                    + "              \"type\": \"string, backend-neutral implementation hint such as math_text|plain_text|background_box|border_box|highlight_ring\",\n"
                    + "              \"properties\": {\n"
                    + "                \"key\": \"value, backend-relevant style property such as color, font_size, fill_opacity, padding, corner_radius, stroke_width, line_style, or label_visible\"\n"
                    + "              }\n"
                    + "            }\n"
                    + "          ]";

    /** Schema for an entering_objects entry: id + optional placement + optional style. */
    public static final String ENTERING_OBJECT_SCHEMA =
            "        {\n"
                    + "          \"id\": \"string, stable visual identity that must match a registry entry; keep ids concise and non-redundant since `kind` carries the type; follow only the active backend's naming rules\",\n"
                    + PLACEMENT_SCHEMA + ",\n"
                    + STYLE_SCHEMA + "\n"
                    + "        }";

    /** Schema for a persistent_objects entry: id + optional placement/style overrides. */
    public static final String PERSISTENT_OBJECT_SCHEMA =
            "        {\n"
                    + "          \"id\": \"string, id of an object that remains visible from previous scenes\",\n"
                    + "          \"placement\": { ...optional, only if position changes... },\n"
                    + "          \"style\": [ ...optional, only if style changes... ]\n"
                    + "        }";

    /** Schema for an exiting_objects entry: id only. */
    public static final String EXITING_OBJECT_SCHEMA =
            "        { \"id\": \"string, id of an object removed in this scene\" }";

    /** Schema for the actions array entries within a scene. */
    public static final String ACTION_SCHEMA =
            "        {\n"
                    + "          \"order\": \"integer, execution order within the scene\",\n"
                    + "          \"type\": \"string, action category such as create|write|transform|highlight|move|fade_out|camera; each action should correspond to one learner-visible beat or one small grouped beat\",\n"
                    + "          \"targets\": [\n"
                    + "            \"string, object id mainly affected by the action\"\n"
                    + "          ],\n"
                    + "          \"description\": \"string, precise visual action intent and visible change, including why the learner should notice this beat\"\n"
                    + "        }";

    /** Schema for the notes_for_codegen array entries within a scene. */
    public static final String NOTES_FOR_CODEGEN_SCHEMA =
            "        \"string, implementation hint that helps downstream generation preserve intent\"";

    /** Full scene-field block (scene_id through notes_for_codegen) excluding the wrapping braces. */
    public static final String SCENE_FIELDS_SCHEMA =
            "    \"scene_id\": \"string, stable unique scene id\",\n"
                    + "    \"title\": \"string, short production label for the scene\",\n"
                    + "    \"goal\": \"string, what the learner should understand or what solving progress should be achieved by the end of the scene\",\n"
                    + "    \"narration\": \"string, concise learner-facing voiceover text for this scene only; its sentences should align with visible beats\",\n"
                    + "    \"duration_seconds\": \"integer, approximate runtime for pacing\",\n"
                    + "    \"scene_mode\": \"string, 2d by default or 3d only when depth is essential\",\n"
                    + "    \"camera_anchor\": \"string, main camera focus region or anchor object\",\n"
                    + "    \"camera_plan\": \"string, how the camera behaves in this scene\",\n"
                    + "    \"layout_goal\": \"string, intended screen composition and relative placement of major elements, including where the main visual focus and empty breathing room should be\",\n"
                    + "    \"safe_area_plan\": \"string, how important content stays readable and inside the safe frame\",\n"
                    + "    \"screen_overlay_plan\": \"string, what text or formulas stay fixed relative to the viewport rather than the main geometry, and where the safe overlay zone is\",\n"
                    + "    \"geometry_constraints\": [\"string, hard geometric invariants that downstream codegen and fix stages must preserve\"],\n"
                    + "    \"step_refs\": [\"string, referenced knowledge-graph step or solving beat covered by this scene\"],\n"
                    + "    \"entering_objects\": [\n"
                    + ENTERING_OBJECT_SCHEMA + "\n"
                    + "    ],\n"
                    + "    \"persistent_objects\": [\n"
                    + PERSISTENT_OBJECT_SCHEMA + "\n"
                    + "    ],\n"
                    + "    \"exiting_objects\": [\n"
                    + EXITING_OBJECT_SCHEMA + "\n"
                    + "    ],\n"
                    + "    \"actions\": [\n"
                    + ACTION_SCHEMA + "\n"
                    + "    ],\n"
                    + "    \"notes_for_codegen\": [\n"
                    + NOTES_FOR_CODEGEN_SCHEMA + "\n"
                    + "    ]";

    /** Schema for an object_registry / new_objects entry: identity, content, dependency, and behavior fields. */
    public static final String OBJECT_DEFINITION_SCHEMA =
            "    {\n"
                    + "      \"id\": \"string, stable visual identity for continuity and transforms; keep ids concise and non-redundant since `kind` carries the type; follow only the active backend's naming rules\",\n"
                    + "      \"kind\": \"string, object category such as text|equation|axes|point|graph|label|region|helper; do not repeat this type inside `id`\",\n"
                    + "      \"content\": \"string, mathematical or visual content shown by the object; if this text references other storyboard objects, mention those objects by id only and do not repeat their kind\",\n"
                    + "      \"source_node\": \"string, originating step or node when relevant\",\n"
                    + "      \"behavior\": \"string, dependency role such as static|follows_anchor|derived|fixed_overlay\",\n"
                    + "      \"anchor_id\": \"string, id of the object this one should stay attached to when relevant\",\n"
                    + "      \"dependency_note\": \"string, short note describing what source objects define this object\",\n"
                    + "      \"constraint_note\": \"string, hard local geometric rule for this object\"\n"
                    + "    }";

    // ── Example data ───────────────────────────────────────────────────

    /** Example object-registry entry: a static number line. */
    public static final String EXAMPLE_NUMBER_LINE =
            "    {\n"
                    + "      \"id\": \"numberLine\",\n"
                    + "      \"kind\": \"line\",\n"
                    + "      \"content\": \"Number line from -2 to 6 with integer ticks\",\n"
                    + "      \"source_node\": \"problem_setup\",\n"
                    + "      \"behavior\": \"static\",\n"
                    + "      \"anchor_id\": \"\",\n"
                    + "      \"dependency_note\": \"independent baseline\",\n"
                    + "      \"constraint_note\": \"fixed baseline\"\n"
                    + "    }";

    /** Example object-registry entry: a derived moving point. */
    public static final String EXAMPLE_POINT_P =
            "    {\n"
                    + "      \"id\": \"P\",\n"
                    + "      \"kind\": \"point\",\n"
                    + "      \"content\": \"Moving point on numberLine\",\n"
                    + "      \"source_node\": \"problem_setup\",\n"
                    + "      \"behavior\": \"derived\",\n"
                    + "      \"anchor_id\": \"numberLine\",\n"
                    + "      \"dependency_note\": \"point on numberLine; draggable along it\",\n"
                    + "      \"constraint_note\": \"lies on numberLine\"\n"
                    + "    }";

    /** Example object-registry entry: a fixed-overlay formula card. */
    public static final String EXAMPLE_FORMULA_CARD =
            "    {\n"
                    + "      \"id\": \"formulaCard\",\n"
                    + "      \"kind\": \"text_card\",\n"
                    + "      \"content\": \"min = 2 for x in [1,3]\",\n"
                    + "      \"source_node\": \"minimum_reveal\",\n"
                    + "      \"behavior\": \"fixed_overlay\",\n"
                    + "      \"anchor_id\": \"\",\n"
                    + "      \"dependency_note\": \"\",\n"
                    + "      \"constraint_note\": \"\"\n"
                    + "    }";

    /** Example object-registry entry: a derived minimum marker. */
    public static final String EXAMPLE_MIN_MARKER =
            "    {\n"
                    + "      \"id\": \"minMarker\",\n"
                    + "      \"kind\": \"point\",\n"
                    + "      \"content\": \"Minimum point marker\",\n"
                    + "      \"source_node\": \"minimum_reveal\",\n"
                    + "      \"behavior\": \"derived\",\n"
                    + "      \"anchor_id\": \"numberLine\",\n"
                    + "      \"dependency_note\": \"minimum of the function on numberLine\",\n"
                    + "      \"constraint_note\": \"lies on numberLine at the minimum\"\n"
                    + "    }";

    /** Example scene 1 entering_objects with numberLine, P, and formulaCard. */
    public static final String EXAMPLE_SCENE1_ENTERING_OBJECTS =
            "    \"entering_objects\": [\n"
                    + "      {\n"
                    + "        \"id\": \"numberLine\",\n"
                    + "        \"placement\": {\n"
                    + "          \"coordinate_space\": \"world\",\n"
                    + "          \"x\": { \"min\": -3, \"max\": 3 },\n"
                    + "          \"y\": { \"value\": 0 }\n"
                    + "        }\n"
                    + "      },\n"
                    + "      {\n"
                    + "        \"id\": \"P\",\n"
                    + "        \"placement\": {\n"
                    + "          \"coordinate_space\": \"world\",\n"
                    + "          \"x\": { \"value\": 2 },\n"
                    + "          \"y\": { \"value\": 0 }\n"
                    + "        }\n"
                    + "      },\n"
                    + "      {\n"
                    + "        \"id\": \"formulaCard\",\n"
                    + "        \"placement\": {\n"
                    + "          \"coordinate_space\": \"world\",\n"
                    + "          \"x\": { \"value\": 0 },\n"
                    + "          \"y\": { \"value\": 2 }\n"
                    + "        },\n"
                    + "        \"style\": [\n"
                    + "          {\n"
                    + "            \"role\": \"text\",\n"
                    + "            \"type\": \"math_text\",\n"
                    + "            \"properties\": {\n"
                    + "              \"color\": \"BLACK\",\n"
                    + "              \"font_size\": 30,\n"
                    + "              \"z_index\": 2\n"
                    + "            }\n"
                    + "          },\n"
                    + "          {\n"
                    + "            \"role\": \"background\",\n"
                    + "            \"type\": \"background_box\",\n"
                    + "            \"properties\": {\n"
                    + "              \"fill_color\": \"WHITE\",\n"
                    + "              \"fill_opacity\": 1,\n"
                    + "              \"stroke_color\": \"WHITE\",\n"
                    + "              \"stroke_width\": 1,\n"
                    + "              \"corner_radius\": 0.2,\n"
                    + "              \"padding\": 0.2\n"
                    + "            }\n"
                    + "          }\n"
                    + "        ]\n"
                    + "      }\n"
                    + "    ]";

    /** Example scene 1 fields common to both VisualDesign and Narrative outputs. */
    public static final String EXAMPLE_SCENE1_BODY =
            "    \"scene_id\": \"scene_1\",\n"
                    + "    \"title\": \"Set Up The Problem\",\n"
                    + "    \"goal\": \"Establish the givens and what must be found.\",\n"
                    + "    \"narration\": \"We first place the diagram and identify the target quantity.\",\n"
                    + "    \"duration_seconds\": 8,\n"
                    + "    \"scene_mode\": \"2d\",\n"
                    + "    \"camera_anchor\": \"center\",\n"
                    + "    \"camera_plan\": \"Static 2D camera.\",\n"
                    + "    \"layout_goal\": \"Keep the main diagram centered and reserve edge space for supporting labels.\",\n"
                    + "    \"safe_area_plan\": \"Keep all important content inside x[-7,7] and y[-4,4] with margin.\",\n"
                    + "    \"screen_overlay_plan\": \"No fixed screen overlay needed.\",\n"
                    + "    \"geometry_constraints\": [\"Keep derived points defined by their construction, not by ad hoc coordinates.\"],\n"
                    + "    \"step_refs\": [\"problem_setup\"],\n"
                    + EXAMPLE_SCENE1_ENTERING_OBJECTS + ",\n"
                    + "    \"persistent_objects\": [],\n"
                    + "    \"exiting_objects\": [],\n"
                    + "    \"actions\": [\n"
                    + "      {\n"
                    + "        \"order\": 1,\n"
                    + "        \"type\": \"create\",\n"
                    + "        \"targets\": [\"numberLine\", \"P\", \"formulaCard\"],\n"
                    + "        \"description\": \"Draw the main diagram, place the moving point, and reveal the conclusion card.\"\n"
                    + "      }\n"
                    + "    ],\n"
                    + "    \"notes_for_codegen\": [\n"
                    + "      \"Reuse numberLine and P in later scenes instead of recreating them.\",\n"
                    + "      \"Only include `style` on objects that need non-default rendering properties.\"\n"
                    + "    ]";

    /** Example scene 2 entering_objects with minMarker. */
    public static final String EXAMPLE_SCENE2_ENTERING_OBJECTS =
            "    \"entering_objects\": [\n"
                    + "      {\n"
                    + "        \"id\": \"minMarker\",\n"
                    + "        \"placement\": {\n"
                    + "          \"coordinate_space\": \"world\",\n"
                    + "          \"x\": { \"value\": 1 },\n"
                    + "          \"y\": { \"value\": 2 }\n"
                    + "        },\n"
                    + "        \"style\": [\n"
                    + "          {\n"
                    + "            \"role\": \"emphasis\",\n"
                    + "            \"type\": \"highlight_ring\",\n"
                    + "            \"properties\": { \"color\": \"YELLOW\", \"stroke_width\": 3 }\n"
                    + "          }\n"
                    + "        ]\n"
                    + "      }\n"
                    + "    ]";

    /** Example scene 2 fields common to both VisualDesign and Narrative outputs. */
    public static final String EXAMPLE_SCENE2_BODY =
            "    \"scene_id\": \"scene_2\",\n"
                    + "    \"title\": \"Reveal The Minimum\",\n"
                    + "    \"goal\": \"Show the minimum value and its location.\",\n"
                    + "    \"narration\": \"The minimum value is 2, occurring at x equals 1.\",\n"
                    + "    \"duration_seconds\": 10,\n"
                    + "    \"scene_mode\": \"2d\",\n"
                    + "    \"camera_anchor\": \"center\",\n"
                    + "    \"camera_plan\": \"Static 2D camera.\",\n"
                    + "    \"layout_goal\": \"Keep the diagram centered; highlight the minimum point.\",\n"
                    + "    \"safe_area_plan\": \"Keep all important content inside x[-7,7] and y[-4,4] with margin.\",\n"
                    + "    \"screen_overlay_plan\": \"No fixed screen overlay needed.\",\n"
                    + "    \"geometry_constraints\": [],\n"
                    + "    \"step_refs\": [\"minimum_reveal\"],\n"
                    + EXAMPLE_SCENE2_ENTERING_OBJECTS + ",\n"
                    + "    \"persistent_objects\": [\n"
                    + "      { \"id\": \"numberLine\" },\n"
                    + "      { \"id\": \"P\" },\n"
                    + "      { \"id\": \"formulaCard\" }\n"
                    + "    ],\n"
                    + "    \"exiting_objects\": [],\n"
                    + "    \"actions\": [\n"
                    + "      {\n"
                    + "        \"order\": 1,\n"
                    + "        \"type\": \"create\",\n"
                    + "        \"targets\": [\"minMarker\"],\n"
                    + "        \"description\": \"Place a highlight marker at the minimum point.\"\n"
                    + "      },\n"
                    + "      {\n"
                    + "        \"order\": 2,\n"
                    + "        \"type\": \"highlight\",\n"
                    + "        \"targets\": [\"minMarker\"],\n"
                    + "        \"description\": \"Pulse the highlight to draw attention.\"\n"
                    + "      }\n"
                    + "    ],\n"
                    + "    \"notes_for_codegen\": []";

    /** Patch-semantics explanation shared by both output formats. */
    public static final String PATCH_SEMANTICS_NOTE =
            "`entering_objects` and `persistent_objects` in each scene are patches: each entry carries only `id` plus optional `placement` and `style`. Do NOT include kind, content, source_node, behavior, anchor_id, dependency_note, or constraint_note there — those belong in the object registry.\n"
                    + "`exiting_objects` entries carry `id` only.\n";

    /** Text style semantics rules shared by both output formats. */
    public static final String TEXT_STYLE_SEMANTICS =
            "Text style semantics are strict:\n"
                    + "- Use `math_text` for formulas, symbolic labels, Greek letters, angle notation, superscripts, subscripts, and any content that should render with `MathTex(...)` downstream.\n"
                    + "- Use `plain_text` for ordinary letters, names, short prose labels, and any content that should render with `Text(...)` downstream.\n"
                    + "- Do not leave mathematical text unlabeled if its rendering choice matters.\n";

    private StoryboardSchemaPrompts() {}
}
