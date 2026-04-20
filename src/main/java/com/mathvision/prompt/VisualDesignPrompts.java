package com.mathvision.prompt;

/**
 * Prompts for Stage 1b: visual design (scene-level output).
 *
 * Each knowledge node produces a full StoryboardScene plus new_objects
 * for the global object registry.
 */
public final class VisualDesignPrompts {

    private static final String SCENE_OUTPUT_FORMAT =
            "Output format:\n"
                    + "Strict JSON syntax requirements:\n"
                    + "- Return one JSON object only. No markdown fence and no prose before or after it.\n"
                    + "- Use double quotes for all keys and all string values.\n"
                    + "- Categorical/string fields must be quoted everywhere, including style properties and action metadata.\n"
                    + "- Allowed unquoted literals are only numbers, true, false, and null.\n"
                    + "Return a JSON object with two top-level keys: `scene` and `new_objects`.\n"
                    + "`new_objects` entries represent the canonical registry definition of each object introduced in this scene. They carry identity, content, dependency, and behavior but not scene-specific placement or style.\n"
                    + "{\n"
                    + "  \"scene\": {\n"
                    + "    \"scene_id\": \"string, stable unique scene id\",\n"
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
                    + "      {\n"
                    + "        \"id\": \"string, stable visual identity for continuity and transforms; keep ids concise and non-redundant since `kind` carries the type; follow only the active backend's naming rules\",\n"
                    + "        \"kind\": \"string, object category such as text|equation|axes|point|graph|label|region|helper; do not repeat this type inside `id`\",\n"
                    + "        \"content\": \"string, mathematical or visual content shown by the object. If this text references other storyboard objects, mention those objects by id only and do not repeat their kind, for example `angle between AP and l at P`. If the object must be visible to the learner, declare it explicitly rather than implying it through another object's prose.\",\n"
                    + "        \"placement\": \"string, explicit initial placement or layout intent relative to the frame or existing anchors; do not use it as the only place to encode hard geometry\",\n"
                    + "        \"style\": [\n"
                    + "          {\n"
                    + "            \"role\": \"string, visual role such as text|background|border|glow|badge|emphasis\",\n"
                    + "            \"type\": \"string, backend-neutral implementation hint such as math_text|plain_text|background_box|border_box|highlight_ring\",\n"
                    + "            \"properties\": {\n"
                    + "              \"key\": \"value, backend-relevant style property such as color, font_size, fill_opacity, padding, corner_radius, stroke_width, line_style, or label_visible\"\n"
                    + "            }\n"
                    + "          }\n"
                    + "        ],\n"
                    + "        \"source_node\": \"string, originating step or node when relevant\",\n"
                    + "        \"behavior\": \"string, dependency role such as static|follows_anchor|derived|fixed_overlay; this does not by itself mean fixed or movable, and `fixed_overlay` is mainly for explanatory text or UI-like overlays rather than native geometry\",\n"
                    + "        \"anchor_id\": \"string, id of the object this one should stay attached to when relevant\",\n"
                    + "        \"dependency_note\": \"string, short note describing what source objects define this object or what it should keep following/updating with; do not omit visible attachment logic\",\n"
                    + "        \"constraint_note\": \"string, hard local geometric rule for this object, such as 'reflection of B across line l', 'lies on l', or 'intersection of AB'' with l'\"\n"
                    + "      }\n"
                    + "    ],\n"
                    + "    \"persistent_objects\": [\n"
                    + "      \"string, id of an object that remains visible from previous scenes\"\n"
                    + "    ],\n"
                    + "    \"exiting_objects\": [\n"
                    + "      \"string, id of an object removed in this scene\"\n"
                    + "    ],\n"
                    + "    \"actions\": [\n"
                    + "      {\n"
                    + "        \"order\": \"integer, execution order within the scene\",\n"
                    + "        \"type\": \"string, action category such as create|write|transform|highlight|move|fade_out|camera; each action should correspond to one learner-visible beat or one small grouped beat\",\n"
                    + "        \"targets\": [\n"
                    + "          \"string, object id mainly affected by the action\"\n"
                    + "        ],\n"
                    + "        \"description\": \"string, precise visual action intent and visible change, including why the learner should notice this beat\"\n"
                    + "      }\n"
                    + "    ],\n"
                    + "    \"notes_for_codegen\": [\n"
                    + "      \"string, implementation hint that helps downstream generation preserve intent\"\n"
                    + "    ]\n"
                    + "  },\n"
                    + "  \"new_objects\": [\n"
                    + "    {\n"
                    + "      \"id\": \"string\",\n"
                    + "      \"kind\": \"string\",\n"
                    + "      \"content\": \"string\",\n"
                    + "      \"source_node\": \"string\",\n"
                    + "      \"behavior\": \"string\",\n"
                    + "      \"anchor_id\": \"string\",\n"
                    + "      \"dependency_note\": \"string\",\n"
                    + "      \"constraint_note\": \"string\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n\n";

    private static final String SCENE_EXAMPLE_OUTPUT =
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
                    + "  \"scene\": {\n"
                    + "    \"scene_id\": \"scene_1\",\n"
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
                    + "    \"entering_objects\": [\n"
                    + "      {\n"
                    + "        \"id\": \"numberLine\",\n"
                    + "        \"kind\": \"line\",\n"
                    + "        \"content\": \"Number line from -2 to 6 with integer ticks\",\n"
                    + "        \"placement\": \"Centered horizontally at y=0\",\n"
                    + "        \"source_node\": \"problem_setup\",\n"
                    + "        \"behavior\": \"static\",\n"
                    + "        \"anchor_id\": \"\",\n"
                    + "        \"dependency_note\": \"independent baseline\",\n"
                    + "        \"constraint_note\": \"fixed baseline\"\n"
                    + "      },\n"
                    + "      {\n"
                    + "        \"id\": \"P\",\n"
                    + "        \"kind\": \"point\",\n"
                    + "        \"content\": \"Moving point on numberLine\",\n"
                    + "        \"placement\": \"Start near (2,0) on numberLine\",\n"
                    + "        \"source_node\": \"problem_setup\",\n"
                    + "        \"behavior\": \"derived\",\n"
                    + "        \"anchor_id\": \"numberLine\",\n"
                    + "        \"dependency_note\": \"point on numberLine; draggable along it\",\n"
                    + "        \"constraint_note\": \"lies on numberLine\"\n"
                    + "      },\n"
                    + "      {\n"
                    + "        \"id\": \"formulaCard\",\n"
                    + "        \"kind\": \"text_card\",\n"
                    + "        \"content\": \"min = 2 for x in [1,3]\",\n"
                    + "        \"placement\": \"Centered above the main diagram\",\n"
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
                    + "        ],\n"
                    + "        \"source_node\": \"minimum_reveal\",\n"
                    + "        \"behavior\": \"fixed_overlay\",\n"
                    + "        \"anchor_id\": \"\",\n"
                    + "        \"dependency_note\": \"\",\n"
                    + "        \"constraint_note\": \"\"\n"
                    + "      }\n"
                    + "    ],\n"
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
                    + "    ]\n"
                    + "  },\n"
                    + "  \"new_objects\": [\n"
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
                    + "    }\n"
                    + "  ]\n"
                    + "}\n\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT + " Do not wrap it in markdown.";

    private static final String SHARED_SCENE_RULES =
            SystemPrompts.OBJECT_LIFECYCLE_RULES
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE
                        .replace("\n- ", "\n- ")
                        .replace("How to interpret the storyboard fields:\n", "Field responsibilities: ").trim() + "\n"
                    + SystemPrompts.GEOMETRY_CONSTRAINT_AUTHORING_RULES;

    private static final String NARRATIVE_DESIGN_RULES =
            "Narrative design rules:\n"
                    + "- Write narration as learner-facing beats: each sentence should correspond to something visible, highlighted, transformed, or deliberately held on screen.\n"
                    + "- Leave breathing room after key reveals; do not imply nonstop motion with no time to read.\n"
                    + "- Duration estimation reference: title card 3-5s, concept introduction 10-20s, equation reveal 15-25s, algorithm step 5-10s, aha-moment beat 15-30s, conclusion 5-10s. Use these ranges when setting `duration_seconds`.\n"
                    + "- Keep object ids concise and non-redundant since `kind` already carries the type. Follow only the naming rules for the active backend.\n"
                    + "- Reuse the exact same concise ids consistently in `anchor_id`, `persistent_objects`, `exiting_objects`, and `actions.targets`.\n"
                    + "- When any field inside `entering_objects` refers to another object, especially `content`, refer to that object by id only. Do not restate its kind there.\n"
                    + "- Prefer structured `style` arrays over vague prose. Each style entry should describe one visual layer or role, such as text, background, border, glow, or emphasis.\n"
                    + "- Do not use a free-text `instructions` field inside style entries. Encode visual intent directly in `properties` using concrete keys and values.\n"
                    + "- For text cards, formulas with badges, boxed labels, counters, or callouts, encode separate text and background layers as separate entries inside `style`.\n"
                    + "- Only include `style` when it adds meaningful rendering properties; omit it for visually plain objects.\n"
                    + "- When the current step merges multiple prerequisite branches, treat the scene as a convergence beat: inherit existing object names, color meanings, and continuity anchors instead of restarting the story.\n"
                    + "- For merge scenes, combine upstream conclusions into one coherent scene and do not replay each branch as if it were brand new.\n"
                    + "- When a temporary element has served its purpose, include it in `exiting_objects` of the current or next scene.\n"
                    + "- Place formulas near edges, not over the main geometry.\n";

    private static final String MANIM_SYSTEM =
            "You are a Manim-first visual designer for math teaching visualizations.\n"
                    + "You are designing ONE scene at a time for a sequential storyboard. Each knowledge-graph node becomes one scene.\n"
                    + "Use the conversation history and object registry to maintain visual continuity with previous scenes.\n"
                    + "Turn abstract reasoning into a learner-facing visual plan before any code is written.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS
                    + SHARED_SCENE_RULES
                    + NARRATIVE_DESIGN_RULES
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "Manim visual-planning constraints:\n"
                    + "- " + SystemPrompts.MANIM_LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed for the teaching goal.\n"
                    + "- Plan where the eye should look first, what remains as dim context, and what area stays open for overlays or later reveals.\n"
                    + "- Prefer dark backgrounds (#1C1C1C to #2D2B55) with light content for maximum contrast and cinema feel.\n"
                    + "- Assign colors to concepts, not to individual objects. Once a color is assigned to a concept, it keeps that meaning across the entire presentation.\n"
                    + "- Plan per-scene variation: vary the dominant color, spatial layout, animation entry style, and visual density across scenes. Never use identical visual config for every scene.\n"
                    + "- Prefer a stable world layout and meaningful transforms over repeatedly replacing the whole diagram.\n"
                    + "- Distinguish what should animate from what should stay static; motion is not mandatory.\n"
                    + "- The plan must be implementable with documented Manim constructs and no hidden assumptions.\n\n"
                    + SCENE_OUTPUT_FORMAT
                    + "\n"
                    + SCENE_EXAMPLE_OUTPUT;

    private static final String GEOGEBRA_SYSTEM =
            "You are a visual designer for GeoGebra teaching constructions.\n"
                    + "You are designing ONE scene at a time for a sequential storyboard. Each knowledge-graph node becomes one scene.\n"
                    + "Use the conversation history and object registry to maintain visual continuity with previous scenes.\n"
                    + "Turn abstract reasoning into something the learner can see, compare, or manipulate.\n"
                    + "Do not invent unsupported givens or alternative solution branches.\n\n"
                    + SystemPrompts.NARRATIVE_PHILOSOPHY
                    + SystemPrompts.VISUAL_PLANNING_RULES
                    + SystemPrompts.COMPOSITION_RULES
                    + SHARED_SCENE_RULES
                    + NARRATIVE_DESIGN_RULES
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "Visual design principles:\n"
                    + "- Prefer direct visual reasoning over text-heavy explanation.\n"
                    + "- Keep the learner oriented around one stable construction when possible.\n"
                    + "- Let formulas support the visual argument instead of replacing it.\n"
                    + "- If a reasoning step is not naturally visible, design a faithful construction-based proxy.\n"
                    + SystemPrompts.HIGH_CONTRAST_COLOR_RULES_BULLETS + "\n"
                    + "GeoGebra planning constraints:\n"
                    + "- " + SystemPrompts.LAYOUT_FRAME_RULES.replace("\n", "\n- ").trim() + "\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed.\n"
                    + "- Keep the visual plan implementable without hidden assumptions.\n"
                    + "- Prefer readable construction layout and clear label placement over animation-like staging language.\n\n"
                    + SCENE_OUTPUT_FORMAT
                    + "\n"
                    + SCENE_EXAMPLE_OUTPUT;

    private VisualDesignPrompts() {}

    public static String systemPrompt(String targetConcept,
                                      String targetDescription,
                                      String outputTarget,
                                      String solutionChain) {
        String prompt = SystemPrompts.buildWorkflowPrefix(
                "Stage 1b / Visual Design",
                "Scene visual design",
                targetConcept,
                targetDescription,
                outputTarget
        ) + ("geogebra".equalsIgnoreCase(outputTarget)
                ? "Design for GeoGebra as an interactive construction medium.\n\n" + GEOGEBRA_SYSTEM
                : "Design for Manim as a teaching animation medium rather than a backend-neutral compromise.\n\n"
                + MANIM_SYSTEM);
        if (solutionChain != null && !solutionChain.isBlank()) {
            prompt += "\n\n" + solutionChain;
        }
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return SystemPrompts.ensureGeoGebraStyleReference(prompt);
        }
        return SystemPrompts.ensureManimStyleReference(prompt);
    }
}
