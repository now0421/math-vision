package com.mathvision.prompt;

import com.mathvision.util.StoryboardConstraintCatalog;

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
                    + "- Use double quotes for all JSON keys and all string values, including categorical fields such as kind, scene_mode, action type, line_style, hex colors, and label content.\n"
                    + "- Do not output markdown fences, comments, trailing commas, or single-quoted strings.\n"
                    + "- Do not output bare identifiers as JSON values. Invalid: \"type\": create. Valid: \"type\": \"create\".\n";

    /** Invalid vs. valid JSON examples that demonstrate common quoting mistakes. */
    public static final String JSON_LEXICAL_EXAMPLES =
            "Invalid examples to avoid:\n"
                    + "- {\"type\": create}\n"
                    + "- {\"kind\": point}\n"
                    + "- {\"style\": {\"color\": #FACC15}}\n"
                    + "Valid equivalents:\n"
                    + "- {\"type\": \"create\"}\n"
                    + "- {\"kind\": \"point\"}\n"
                    + "- {\"style\": {\"color\": \"#FACC15\"}}\n";

    // ── Field-level schemas ────────────────────────────────────────────

    /** The placement object schema used in entering_objects and persistent_objects patches. */
    public static final String PLACEMENT_SCHEMA =
            "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"string, one of world|screen|anchor\",\n"
                    + "            \"x\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" },\n"
                    + "            \"y\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" },\n"
                    + "            \"z\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" }\n"
                    + "          }";

    /** The typed style object schema used in entering_objects and persistent_objects patches. */
    public static final String STYLE_SCHEMA =
            "          \"style\": {\n"
                    + "            \"color\": \"#RRGGBB primary foreground color (for text/equation: text color; for point: dot color; for line: fallback stroke color)\",\n"
                    + "            \"fill_color\": \"#RRGGBB fill color (for shapes: interior fill; for text_card: background box fill)\",\n"
                    + "            \"stroke_color\": \"#RRGGBB stroke/border color (for lines: line color; for shapes: border; for text_card: background box border)\",\n"
                    + "            \"highlight_color\": \"#RRGGBB emphasis color\",\n"
                    + "            \"font_family\": \"string\",\n"
                    + "            \"font_weight\": \"string\",\n"
                    + "            \"font_style\": \"string\",\n"
                    + "            \"line_style\": \"solid|dashed|dotted|dash_dot\",\n"
                    + "            \"opacity\": \"number 0..1\",\n"
                    + "            \"fill_opacity\": \"number 0..1 (for shapes: interior transparency; for text_card: background box fill transparency)\",\n"
                    + "            \"stroke_opacity\": \"number 0..1 (for lines: line transparency; for text_card: background box border transparency)\",\n"
                    + "            \"stroke_width\": \"number\",\n"
                    + "            \"font_size\": \"number\",\n"
                    + "            \"padding\": \"number (text_card only)\",\n"
                    + "            \"corner_radius\": \"number (text_card only)\",\n"
                    + "            \"z_index\": \"number\",\n"
                    + "            \"point_size\": \"number (GeoGebra: SetPointSize)\",\n"
                    + "            \"radius\": \"number (point dot radius, circle radius, or arc radius)\",\n"
                    + "            \"marker_size\": \"number (angle/tick marker size)\",\n"
                    + "            \"point_style\": \"number (GeoGebra only: SetPointStyle)\",\n"
                    + "            \"decoration\": \"number (GeoGebra only: SetDecoration)\",\n"
                    + "            \"label_visible\": \"boolean (GeoGebra only: ShowLabel)\"\n"
                    + "          }";

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
                    + "          \"style\": { ...optional typed style object, only if style changes... }\n"
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
            "        \"string, hard scene-level implementation constraint that downstream generation and repair must preserve\"";

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
                    + "    \"constraints\": [\"object, machine-readable scene-level invariant with domain, relation, refs, optional parameters, strength, and reason\"],\n"
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

    /** Schema for an object_registry / new_objects entry: identity, content, style, and hard constraints. */
    public static final String OBJECT_DEFINITION_SCHEMA =
            "    {\n"
                    + "      \"id\": \"string, stable visual identity for continuity and transforms; keep ids concise and non-redundant since `kind` carries the type; follow only the active backend's naming rules\",\n"
                    + "      \"kind\": \"string, concrete render/construction primitive such as point|line|ray|segment|vector|circle|arc|angle_marker|right_angle_marker|polygon|polyline|axes|number_line|function_curve|parametric_curve|implicit_curve|conic|region|brace|tick_marker|distance_marker|text|equation|image; prefer text/equation over text_card/formula_card (cards should only be used when the box itself is teaching-essential); avoid broad kinds like graph/helper when a concrete primitive fits; do not repeat this type inside `id`\",\n"
                    + "      \"content\": \"string, mathematical or visual content shown by the object; if this text references other storyboard objects, mention those objects by id only and do not repeat their kind\",\n"
                    + STYLE_SCHEMA + ",\n"
                    + "      \"constraints\": [\n"
                    + "        {\n"
                    + "          \"id\": \"string, optional stable id for this single invariant\",\n"
                    + "          \"domain\": \"" + StoryboardConstraintCatalog.domainList() + "\",\n"
                    + "          \"relation\": \"string, one of " + StoryboardConstraintCatalog.relationList() + "\",\n"
                    + "          \"refs\": { \"canonical_role\": \"object id or [id list]; use ONLY the role names from the constraint catalog in the rules section above\" },\n"
                    + "          \"parameters\": { \"param_name\": \"string, number, boolean, array, or object; use ONLY catalog parameter names; NEVER put object ids here\" },\n"
                    + "          \"strength\": \"hard (mathematical/semantic invariant) | repair_hard (required during cleanup) | soft (preference)\",\n"
                    + "          \"reason\": \"string, short human-readable explanation\"\n"
                    + "        }\n"
                    + "      ]\n"
                    + "    }";

    // ── Example data ───────────────────────────────────────────────────

    /** Example object-registry entry: a static number line. */
    public static final String EXAMPLE_NUMBER_LINE =
            "    {\n"
                    + "      \"id\": \"numberLine\",\n"
                    + "      \"kind\": \"line\",\n"
                    + "      \"content\": \"Number line from -2 to 6 with integer ticks\"\n"
                    + "    }";

    /** Example object-registry entry: a derived moving point. */
    public static final String EXAMPLE_POINT_P =
            "    {\n"
                    + "      \"id\": \"P\",\n"
                    + "      \"kind\": \"point\",\n"
                    + "      \"content\": \"Moving point on numberLine\",\n"
                    + "      \"constraints\": [\n"
                    + "        {\n"
                    + "          \"domain\": \"geometry\",\n"
                    + "          \"relation\": \"lies_on\",\n"
                    + "          \"refs\": {\"point\": \"P\", \"support\": \"numberLine\"},\n"
                    + "          \"strength\": \"hard\",\n"
                    + "          \"reason\": \"P must stay on numberLine\"\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"domain\": \"motion\",\n"
                    + "          \"relation\": \"moves_on_object\",\n"
                    + "          \"refs\": {\"point\": \"P\", \"support\": \"numberLine\"},\n"
                    + "          \"parameters\": {\"range\": \"visible_line\"},\n"
                    + "          \"strength\": \"hard\",\n"
                    + "          \"reason\": \"P slides along numberLine\"\n"
                    + "        }\n"
                    + "      ]\n"
                    + "    }";

    private static final String EXAMPLE_POINT_P_LABEL_BODY =
            "    {\n"
                    + "      \"id\": \"labelP\",\n"
                    + "      \"kind\": \"text\",\n"
                    + "      \"content\": \"P\",\n"
                    + "      \"constraints\": [\n"
                    + "        {\n"
                    + "          \"domain\": \"attachment\",\n"
                    + "          \"relation\": \"label_for\",\n"
                    + "          \"refs\": {\"label\": \"labelP\", \"anchor\": \"P\"},\n"
                    + "          \"parameters\": {\"side\": \"up_right\"},\n"
                    + "          \"strength\": \"hard\",\n"
                    + "          \"reason\": \"labelP follows P\"\n"
                    + "        }\n"
                    + "      ]\n"
                    + "    }";

    /** Backend-specific example snippet: a companion text label attached to a point. */
    public static final String MANIM_COMPANION_LABEL_EXAMPLE =
            "Companion label example:\n"
                    + "Add the label as its own registry object:\n"
                    + EXAMPLE_POINT_P_LABEL_BODY
                    + "\n"
                    + "Then include it as a scene patch beside its parent:\n"
                    + "{\n"
                    + "  \"id\": \"labelP\",\n"
                    + "  \"placement\": {\n"
                    + "    \"coordinate_space\": \"anchor\",\n"
                    + "    \"x\": { \"value\": 0.25 },\n"
                    + "    \"y\": { \"value\": 0.25 }\n"
                    + "  },\n"
                    + "  \"style\": {\n"
                    + "    \"color\": \"#FFFFFF\",\n"
                    + "    \"font_size\": 24\n"
                    + "  }\n"
                    + "}\n";

    /** Example object-registry entry: a fixed-overlay formula. */
    public static final String EXAMPLE_FORMULA_CARD =
            "    {\n"
                    + "      \"id\": \"formulaCard\",\n"
                    + "      \"kind\": \"equation\",\n"
                    + "      \"content\": \"min = 2 for x in [1,3]\",\n"
                    + "      \"constraints\": [\n"
                    + "        {\n"
                    + "          \"domain\": \"attachment\",\n"
                    + "          \"relation\": \"fixed_overlay\",\n"
                    + "          \"refs\": {\"object\": \"formulaCard\"},\n"
                    + "          \"parameters\": {\"coordinate_space\": \"screen\"},\n"
                    + "          \"strength\": \"hard\",\n"
                    + "          \"reason\": \"formulaCard remains a screen overlay\"\n"
                    + "        }\n"
                    + "      ]\n"
                    + "    }";

    /** Example object-registry entry: a derived minimum marker. */
    public static final String EXAMPLE_MIN_MARKER =
            "    {\n"
                    + "      \"id\": \"minMarker\",\n"
                    + "      \"kind\": \"point\",\n"
                    + "      \"content\": \"Minimum point marker\",\n"
                    + "      \"constraints\": [\n"
                    + "        {\n"
                    + "          \"domain\": \"geometry\",\n"
                    + "          \"relation\": \"lies_on\",\n"
                    + "          \"refs\": {\"point\": \"minMarker\", \"support\": \"numberLine\"},\n"
                    + "          \"strength\": \"hard\",\n"
                    + "          \"reason\": \"minimum marker stays on numberLine\"\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"domain\": \"measurement\",\n"
                    + "          \"relation\": \"minimum_of\",\n"
                    + "          \"refs\": {\"marker\": \"minMarker\", \"support\": \"numberLine\"},\n"
                    + "          \"parameters\": {\"objective\": \"displayed function value\"},\n"
                    + "          \"strength\": \"hard\",\n"
                    + "          \"reason\": \"minMarker identifies the minimum\"\n"
                    + "        }\n"
                    + "      ]\n"
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
                    + "        \"style\": {\n"
                    + "          \"color\": \"#111827\",\n"
                    + "          \"font_size\": 30,\n"
                    + "          \"z_index\": 2,\n"
                    + "          \"fill_color\": \"#FFFFFF\",\n"
                    + "          \"fill_opacity\": 1,\n"
                    + "          \"stroke_color\": \"#FFFFFF\",\n"
                    + "          \"stroke_width\": 1,\n"
                    + "          \"corner_radius\": 0.2,\n"
                    + "          \"padding\": 0.2\n"
                    + "        }\n"
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
                    + "    \"constraints\": [\n"
                    + "      {\n"
                    + "        \"domain\": \"motion\",\n"
                    + "        \"relation\": \"moves_on_object\",\n"
                    + "        \"refs\": {\"point\": \"P\", \"support\": \"numberLine\"},\n"
                    + "        \"parameters\": {\"range\": \"visible_line\"},\n"
                    + "        \"strength\": \"hard\",\n"
                    + "        \"reason\": \"P remains constrained to numberLine while it moves.\"\n"
                    + "      }\n"
                    + "    ],\n"
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
                    + "        \"style\": { \"highlight_color\": \"#FACC15\", \"stroke_width\": 3 }\n"
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
                    + "    \"constraints\": [],\n"
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
            "`entering_objects` and `persistent_objects` in each scene are patches: each entry carries only `id` plus optional `placement` and `style`. Do NOT include kind, content, or constraints there - those belong in the object registry.\n"
                    + "`exiting_objects` entries carry `id` only.\n";

    /** Text style semantics rules shared by both output formats. */
    public static final String TEXT_STYLE_SEMANTICS =
            "Text object semantics are strict:\n"
                    + "- Use `kind = equation` for formulas, symbolic labels, Greek letters, angle notation, superscripts, subscripts, and any content that should render with `MathTex(...)` downstream.\n"
                    + "- Use `kind = text` for ordinary letters, names, short prose labels, and any content that should render with `Text(...)` downstream.\n"
                    + "- Avoid `kind = text_card` and `kind = formula_card`; display text directly without a background box unless the card itself is teaching-essential (e.g. a titled result panel). Most formulas and labels are clearer as plain `text` or `equation` objects.\n"
                    + "- Do not encode text constructor choice in `style`; `style` only contains typed rendering properties.\n";

    private StoryboardSchemaPrompts() {}
}
