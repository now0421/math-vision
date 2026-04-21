package com.mathvision.prompt;

import com.mathvision.model.Narrative.Storyboard;

/**
 * Prompts for storyboard validation and codegen-prompt assembly.
 * Used by StoryboardValidationNode (Stage 1c) and CodeGenerationNode (Stage 2).
 * Scene-level design rules live in {@link VisualDesignPrompts}.
 */
public final class NarrativePrompts {

    private static final String COMMON_RULES =
            "You are a STEM narrative designer validating and fixing a structured storyboard for a math teaching visualization.\n"
                    + "The storyboard functions as a visual presentation plan rather than a written solution.\n"
                    + "Introduce foundations before advanced content, and keep the storyboard continuity-safe.\n\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "Layout rules:\n"
                    + "- Frame is 16:9 with " + SystemPrompts.LAYOUT_FRAME_RULES.toLowerCase()
                    .replace("keep important content within", "important content kept inside")
                    .replace("usually keep each step to", "keep simultaneous main visual elements around")
                    .replace(".\n", "\n- ").trim() + "\n"
                    + "- Place formulas near edges, not over the main geometry\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE
                    .replace("\n- ", "\n- ")
                    .replace("How to interpret the storyboard fields:\n", "Field responsibilities: ").trim() + "\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE_EXTENDED.trim() + "\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_AUTHORING_RULES
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed relative to the viewport rather than the main geometry\n\n"
                    + "Storyboard-level rules:\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "- Plan per-scene variation: vary the dominant visual focus, spatial layout pattern, and visual density across scenes. Avoid identical composition for consecutive scenes\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES + "\n";

    private static final String GEOGEBRA_RULES =
            "GeoGebra-specific storyboard validation rules:\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n";

    private static final String MANIM_RULES =
            "Manim-specific storyboard validation rules:\n"
                    + SystemPrompts.MANIM_MOTION_AND_PACING_RULES
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "- Once a color is assigned to a concept, it keeps that meaning across the entire storyboard. Record color-to-concept assignments in `global_visual_rules`.\n";

    private static final String OUTPUT_FORMAT =
            "Output format:\n"
                    + "Strict JSON syntax requirements:\n"
                    + "- Return one JSON object only. No markdown fence and no prose before or after it.\n"
                    + "- Use double quotes for all keys and all string values.\n"
                    + "- Categorical/string fields must be quoted everywhere, including style properties and action metadata.\n"
                    + "- Allowed unquoted literals are only numbers, true, false, and null.\n"
                    + "Return a JSON object with this shape:\n"
                    + "`entering_objects` and `persistent_objects` in each scene are patches: each entry carries only `id` plus optional `placement` and `style`. Do NOT include kind, content, source_node, behavior, anchor_id, dependency_note, or constraint_note there — those belong in `object_registry`.\n"
                    + "`exiting_objects` entries carry `id` only.\n"
                    + "{\n"
                    + "  \"continuity_plan\": \"string, how object identities, anchors, and layout stay stable across scenes\",\n"
                    + "  \"global_visual_rules\": [\"string, global staging rule that should hold across the whole presentation\"],\n"
                    + "  \"object_registry\": [\n"
                    + "    {\n"
                    + "      \"id\": \"string, stable visual identity for continuity and transforms; keep ids concise and non-redundant since `kind` carries the type; follow only the active backend's naming rules\",\n"
                    + "      \"kind\": \"string, object category such as text|equation|axes|point|graph|label|region|helper; do not repeat this type inside `id`\",\n"
                    + "      \"content\": \"string, mathematical or visual content shown by the object; if this text references other storyboard objects, mention those objects by id only and do not repeat their kind\",\n"
                    + "      \"source_node\": \"string, originating step or node when relevant\",\n"
                    + "      \"behavior\": \"string, dependency role such as static|follows_anchor|derived|fixed_overlay\",\n"
                    + "      \"anchor_id\": \"string, id of the object this one should stay attached to when relevant\",\n"
                    + "      \"dependency_note\": \"string, short note describing what source objects define this object\",\n"
                    + "      \"constraint_note\": \"string, hard local geometric rule for this object\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"scenes\": [\n"
                    + "    {\n"
                    + "      \"scene_id\": \"string, stable unique scene id\",\n"
                    + "      \"title\": \"string, short production label for the scene\",\n"
                    + "      \"goal\": \"string, what the learner should understand or what solving progress should be achieved by the end of the scene\",\n"
                    + "      \"narration\": \"string, concise learner-facing voiceover text for this scene only; its sentences should align with visible beats\",\n"
                    + "      \"duration_seconds\": \"integer, approximate runtime for pacing\",\n"
                    + "      \"scene_mode\": \"string, 2d by default or 3d only when depth is essential\",\n"
                    + "      \"camera_anchor\": \"string, main camera focus region or anchor object\",\n"
                    + "      \"camera_plan\": \"string, how the camera behaves in this scene\",\n"
                    + "      \"layout_goal\": \"string, intended screen composition and relative placement of major elements, including where the main visual focus and empty breathing room should be\",\n"
                    + "      \"safe_area_plan\": \"string, how important content stays readable and inside the safe frame\",\n"
                    + "      \"screen_overlay_plan\": \"string, what text or formulas stay fixed relative to the viewport rather than the main geometry, and where the safe overlay zone is\",\n"
                    + "      \"geometry_constraints\": [\"string, hard geometric invariants that downstream codegen and fix stages must preserve\"],\n"
                    + "      \"step_refs\": [\"string, referenced knowledge-graph step or solving beat covered by this scene\"],\n"
                    + "      \"entering_objects\": [\n"
                    + "        {\n"
                    + "          \"id\": \"string, stable visual identity that must match an object_registry entry; keep ids concise and non-redundant since `kind` carries the type\",\n"
                    + "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"string, one of world|screen|anchor\",\n"
                    + "            \"x\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" },\n"
                    + "            \"y\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" },\n"
                    + "            \"z\": { \"value\": \"number or null\", \"min\": \"number or null\", \"max\": \"number or null\" }\n"
                    + "          },\n"
                    + "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"string, visual role such as text|background|border|glow|badge|emphasis\",\n"
                    + "              \"type\": \"string, backend-neutral implementation hint such as math_text|plain_text|background_box|border_box|highlight_ring\",\n"
                    + "              \"properties\": {\n"
                    + "                \"key\": \"value, backend-relevant style property such as color, font_size, fill_opacity, padding, corner_radius, stroke_width, line_style, or label_visible\"\n"
                    + "              }\n"
                    + "            }\n"
                    + "          ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"persistent_objects\": [\n"
                    + "        {\n"
                    + "          \"id\": \"string, id of an object that remains visible from previous scenes\",\n"
                    + "          \"placement\": { ...optional, only if position changes... },\n"
                    + "          \"style\": [ ...optional, only if style changes... ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"exiting_objects\": [\n"
                    + "        { \"id\": \"string, id of an object removed in this scene\" }\n"
                    + "      ],\n"
                    + "      \"actions\": [\n"
                    + "        {\n"
                    + "          \"order\": \"integer, execution order within the scene\",\n"
                    + "          \"type\": \"string, action category such as create|write|transform|highlight|move|fade_out|camera; each action should correspond to one learner-visible beat or one small grouped beat\",\n"
                    + "          \"targets\": [\n"
                    + "            \"string, object id mainly affected by the action\"\n"
                    + "          ],\n"
                    + "          \"description\": \"string, precise visual action intent and visible change, including why the learner should notice this beat\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"notes_for_codegen\": [\n"
                    + "        \"string, implementation hint that helps downstream generation preserve intent\"\n"
                    + "      ]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n";

    private static final String EXAMPLE_OUTPUT =
            "Example output:\n"
                    + "Invalid examples to avoid:\n"
                    + "- {\"type\": create}\n"
                    + "- {\"behavior\": static}\n"
                    + "- {\"properties\": {\"color\": YELLOW}}\n"
                    + "Valid equivalents:\n"
                    + "- {\"type\": \"create\"}\n"
                    + "- {\"behavior\": \"static\"}\n"
                    + "- {\"properties\": {\"color\": \"YELLOW\"}}\n"
                    + "{\n"
                    + "  \"continuity_plan\": \"Objects keep stable ids across scenes via object_registry. Anchor-based objects follow their anchors.\",\n"
                    + "  \"global_visual_rules\": [\n"
                    + "    \"Keep major content inside the safe frame.\",\n"
                    + "    \"Prefer transforms and persistent anchors over redraws.\"\n"
                    + "  ],\n"
                    + "  \"object_registry\": [\n"
                    + "    {\n"
                    + "      \"id\": \"numberLine\",\n"
                    + "      \"kind\": \"line\",\n"
                    + "      \"content\": \"Number line from -2 to 6 with integer ticks\",\n"
                    + "      \"source_node\": \"problem_setup\",\n"
                    + "      \"behavior\": \"static\",\n"
                    + "      \"anchor_id\": \"\",\n"
                    + "      \"dependency_note\": \"independent baseline\",\n"
                    + "      \"constraint_note\": \"fixed baseline\"\n"
                    + "    },\n"
                    + "    {\n"
                    + "      \"id\": \"P\",\n"
                    + "      \"kind\": \"point\",\n"
                    + "      \"content\": \"Moving point on numberLine\",\n"
                    + "      \"source_node\": \"problem_setup\",\n"
                    + "      \"behavior\": \"derived\",\n"
                    + "      \"anchor_id\": \"numberLine\",\n"
                    + "      \"dependency_note\": \"point on numberLine; draggable along it\",\n"
                    + "      \"constraint_note\": \"lies on numberLine\"\n"
                    + "    },\n"
                    + "    {\n"
                    + "      \"id\": \"formulaCard\",\n"
                    + "      \"kind\": \"text_card\",\n"
                    + "      \"content\": \"min = 2 for x in [1,3]\",\n"
                    + "      \"source_node\": \"minimum_reveal\",\n"
                    + "      \"behavior\": \"fixed_overlay\",\n"
                    + "      \"anchor_id\": \"\",\n"
                    + "      \"dependency_note\": \"\",\n"
                    + "      \"constraint_note\": \"\"\n"
                    + "    },\n"
                    + "    {\n"
                    + "      \"id\": \"minMarker\",\n"
                    + "      \"kind\": \"point\",\n"
                    + "      \"content\": \"Minimum point marker\",\n"
                    + "      \"source_node\": \"minimum_reveal\",\n"
                    + "      \"behavior\": \"derived\",\n"
                    + "      \"anchor_id\": \"numberLine\",\n"
                    + "      \"dependency_note\": \"minimum of the function on numberLine\",\n"
                    + "      \"constraint_note\": \"lies on numberLine at the minimum\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"scenes\": [\n"
                    + "    {\n"
                    + "      \"scene_id\": \"scene_1\",\n"
                    + "      \"title\": \"Set Up The Problem\",\n"
                    + "      \"goal\": \"Establish the givens and what must be found.\",\n"
                    + "      \"narration\": \"We first place the diagram and identify the target quantity.\",\n"
                    + "      \"duration_seconds\": 8,\n"
                    + "      \"scene_mode\": \"2d\",\n"
                    + "      \"camera_anchor\": \"center\",\n"
                    + "      \"camera_plan\": \"Static 2D camera.\",\n"
                    + "      \"layout_goal\": \"Keep the main diagram centered and reserve edge space for supporting labels.\",\n"
                    + "      \"safe_area_plan\": \"Keep all important content inside x[-7,7] and y[-4,4] with margin.\",\n"
                    + "      \"screen_overlay_plan\": \"No fixed screen overlay needed.\",\n"
                    + "      \"geometry_constraints\": [\"Keep derived points defined by their construction, not by ad hoc coordinates.\"],\n"
                    + "      \"step_refs\": [\"problem_setup\"],\n"
                    + "      \"entering_objects\": [\n"
                    + "        {\n"
                    + "          \"id\": \"numberLine\",\n"
                    + "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"world\",\n"
                    + "            \"x\": { \"min\": -3, \"max\": 3 },\n"
                    + "            \"y\": { \"value\": 0 }\n"
                    + "          }\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"id\": \"P\",\n"
                    + "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"world\",\n"
                    + "            \"x\": { \"value\": 2 },\n"
                    + "            \"y\": { \"value\": 0 }\n"
                    + "          }\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"id\": \"formulaCard\",\n"
                    + "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"world\",\n"
                    + "            \"x\": { \"value\": 0 },\n"
                    + "            \"y\": { \"value\": 2 }\n"
                    + "          },\n"
                    + "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"text\",\n"
                    + "              \"type\": \"math_text\",\n"
                    + "              \"properties\": {\n"
                    + "                \"color\": \"BLACK\",\n"
                    + "                \"font_size\": 30,\n"
                    + "                \"z_index\": 2\n"
                    + "              }\n"
                    + "            },\n"
                    + "            {\n"
                    + "              \"role\": \"background\",\n"
                    + "              \"type\": \"background_box\",\n"
                    + "              \"properties\": {\n"
                    + "                \"fill_color\": \"WHITE\",\n"
                    + "                \"fill_opacity\": 1,\n"
                    + "                \"stroke_color\": \"WHITE\",\n"
                    + "                \"stroke_width\": 1,\n"
                    + "                \"corner_radius\": 0.2,\n"
                    + "                \"padding\": 0.2\n"
                    + "              }\n"
                    + "            }\n"
                    + "          ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"persistent_objects\": [],\n"
                    + "      \"exiting_objects\": [],\n"
                    + "      \"actions\": [\n"
                    + "        {\n"
                    + "          \"order\": 1,\n"
                    + "          \"type\": \"create\",\n"
                    + "          \"targets\": [\"numberLine\", \"P\", \"formulaCard\"],\n"
                    + "          \"description\": \"Draw the main diagram, place the moving point, and reveal the conclusion card.\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"notes_for_codegen\": [\n"
                    + "        \"Reuse numberLine and P in later scenes instead of recreating them.\",\n"
                    + "        \"Only include `style` on objects that need non-default rendering properties.\"\n"
                    + "      ]\n"
                    + "    },\n"
                    + "    {\n"
                    + "      \"scene_id\": \"scene_2\",\n"
                    + "      \"title\": \"Reveal The Minimum\",\n"
                    + "      \"goal\": \"Show the minimum value and its location.\",\n"
                    + "      \"narration\": \"The minimum value is 2, occurring at x equals 1.\",\n"
                    + "      \"duration_seconds\": 10,\n"
                    + "      \"scene_mode\": \"2d\",\n"
                    + "      \"camera_anchor\": \"center\",\n"
                    + "      \"camera_plan\": \"Static 2D camera.\",\n"
                    + "      \"layout_goal\": \"Keep the diagram centered; highlight the minimum point.\",\n"
                    + "      \"safe_area_plan\": \"Keep all important content inside x[-7,7] and y[-4,4] with margin.\",\n"
                    + "      \"screen_overlay_plan\": \"No fixed screen overlay needed.\",\n"
                    + "      \"geometry_constraints\": [],\n"
                    + "      \"step_refs\": [\"minimum_reveal\"],\n"
                    + "      \"entering_objects\": [\n"
                    + "        {\n"
                    + "          \"id\": \"minMarker\",\n"
                    + "          \"placement\": {\n"
                    + "            \"coordinate_space\": \"world\",\n"
                    + "            \"x\": { \"value\": 1 },\n"
                    + "            \"y\": { \"value\": 2 }\n"
                    + "          },\n"
                    + "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"emphasis\",\n"
                    + "              \"type\": \"highlight_ring\",\n"
                    + "              \"properties\": { \"color\": \"YELLOW\", \"stroke_width\": 3 }\n"
                    + "            }\n"
                    + "          ]\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"persistent_objects\": [\n"
                    + "        { \"id\": \"numberLine\" },\n"
                    + "        { \"id\": \"P\" },\n"
                    + "        { \"id\": \"formulaCard\" }\n"
                    + "      ],\n"
                    + "      \"exiting_objects\": [],\n"
                    + "      \"actions\": [\n"
                    + "        {\n"
                    + "          \"order\": 1,\n"
                    + "          \"type\": \"create\",\n"
                    + "          \"targets\": [\"minMarker\"],\n"
                    + "          \"description\": \"Place a highlight marker at the minimum point.\"\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"order\": 2,\n"
                    + "          \"type\": \"highlight\",\n"
                    + "          \"targets\": [\"minMarker\"],\n"
                    + "          \"description\": \"Pulse the highlight to draw attention.\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"notes_for_codegen\": []\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n\n";

    private static final String SYSTEM =
            COMMON_RULES
                    + OUTPUT_FORMAT
                    + "\n"
                    + EXAMPLE_OUTPUT
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT + " Do not wrap it in markdown.";

    private NarrativePrompts() {}

    public static String systemPrompt(String targetConcept,
                                      String targetDescription,
                                      String outputTarget) {
        String prompt = SystemPrompts.buildWorkflowPrefix(
                "Stage 1c / Storyboard Validation",
                "Storyboard composition and validation",
                targetConcept,
                targetDescription,
                outputTarget
        ) + "Output target backend: " + outputTarget + ".\n"
                + "Keep the storyboard reusable, but make it practical for this backend.\n";

        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            prompt += "\n" + GEOGEBRA_RULES;
        } else if ("manim".equalsIgnoreCase(outputTarget)) {
            prompt += "\n" + MANIM_RULES;
        }

        prompt += "\n" + SYSTEM;

        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureGeoGebraStyleReference(prompt);
        }
        if ("manim".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureManimStyleReference(prompt);
        }
        return prompt;
    }

    public static String storyboardCodegenPrompt(Storyboard storyboard) {
        return storyboardCodegenPrompt(storyboard, "manim");
    }

    public static String storyboardCodegenPrompt(Storyboard storyboard,
                                                 String outputTarget) {
        return storyboardCodegenPrompt(
                StoryboardJsonBuilder.buildForCodegen(storyboard),
                outputTarget);
    }

    public static String storyboardCodegenPrompt(String storyboardJson) {
        return storyboardCodegenPrompt(storyboardJson, "manim");
    }

    public static String storyboardCodegenPrompt(String storyboardJson,
                                                 String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return String.format(
                    "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                            + "Remember: Return ONLY the single GeoGebra code block. No explanation.",
                    storyboardJson);
        }
        return String.format(
                "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                storyboardJson);
    }
}
