package com.mathvision.prompt;

import com.mathvision.model.Narrative.Storyboard;

/**
 * Prompts for Stage 1c: narrative composition.
 */
public final class NarrativePrompts {

    private static final String COMMON_RULES =
            "You are a STEM narrative designer writing a structured storyboard for a math teaching visualization.\n"
                    + "Write a scene-by-scene storyboard that functions as a visual presentation plan rather than a written solution.\n"
                    + "Begin with a clear hook, introduce foundations before advanced content, and keep the storyboard continuity-safe.\n\n"
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
                    + SystemPrompts.GEOMETRY_CONSTRAINT_AUTHORING_RULES
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed relative to the viewport rather than the main geometry\n\n"
                    + "Narrative rules:\n"
                    + "- Narrative must not be constrained by a fixed word count\n"
                    + "- Use enrichment fields only when they sharpen the explanation\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "- Write narration as learner-facing beats: each sentence should correspond to something visible, highlighted, transformed, or deliberately held on screen\n"
                    + "- Leave breathing room after key reveals; the storyboard should not imply nonstop motion with no time to read\n"
                    + "- Plan scene transitions intentionally: choose clean break (fade all, pause), carry-forward (keep one anchor, fade rest), or transform bridge for each scene boundary. Record the chosen style in `notes_for_codegen` when the intent is non-obvious\n"
                    + "- Plan per-scene variation: vary the dominant visual focus, spatial layout pattern, and visual density across scenes. Avoid identical composition for consecutive scenes\n"
                    + "- Duration estimation reference: title card 3–5s, concept introduction 10–20s, equation reveal 15–25s, algorithm step 5–10s, aha-moment beat 15–30s, conclusion 5–10s. Use these ranges when setting `duration_seconds`\n"
                    + "- Keep object ids concise and non-redundant since `kind` already carries the type. Follow only the naming rules for the active backend.\n"
                    + "- Reuse the exact same concise ids consistently in `anchor_id`, `persistent_objects`, `exiting_objects`, and `actions.targets`\n"
                    + "- When any field inside `entering_objects` refers to another object, especially `content`, refer to that object by id only. Do not restate its kind there.\n"
                    + "- For example, write `angle between AP and l at P`, not `angle between segment AP and line l at point P`.\n"
                    + "- Prefer structured `style` arrays over vague prose. Each style entry should describe one visual layer or role, such as text, background, border, glow, or emphasis.\n"
                    + "- Do not use a free-text `instructions` field inside style entries. Encode visual intent directly in `properties` using concrete keys and values.\n"
                    + "- For text cards, formulas with badges, boxed labels, counters, or callouts, encode separate text and background layers as separate entries inside `style`.\n"
                    + "- Only include `style` when it adds meaningful rendering properties; omit it for visually plain objects.\n"
                    + "- JSON lexical contract is strict: use double quotes for all JSON keys and all string values, including categorical fields such as kind, behavior, scene_mode, action type, style role/type, color names, and label content.\n"
                    + "- Do not output markdown fences, comments, trailing commas, or single-quoted strings.\n"
                    + "- Do not output bare identifiers as JSON values. Invalid: \"type\": create. Valid: \"type\": \"create\".\n"
                    + "- When a temporary element has served its purpose, include it in `exiting_objects` of the current or next scene\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES + "\n";

    private static final String GEOGEBRA_RULES =
            "GeoGebra-specific storyboard rules:\n"
                    + "- Follow GeoGebra naming conventions.\n"
                    + "- Prefer native GeoGebra labels for named geometric objects such as points, lines, segments, rays, circles, and polygons.\n"
                    + "- If the visible text is just the object's own name or symbol, keep it as the object's native label rather than creating a separate `label` or `text` storyboard object.\n"
                    + "- Create separate `label` or `text` objects only for overlays, formulas, counters, captions, explanatory annotations, or text that is semantically different from the object's native label. Avoid redundant pairs such as `A` plus `aLabel`, `lineL` plus `labelL`, or `circleO` plus `labelO`.\n"
                    + SystemPrompts.GEOGEBRA_NAMING_RULES
                    + "- Use `fixed_overlay` mainly for explanatory text, counters, captions, formulas, and similar viewport-fixed overlays. For geometric points, lines, circles, angle markers, and bullseye-style highlights that belong to the construction, prefer `static` or `derived` unless the object is truly an overlay.\n"
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n"
                    + "- Do not mention specific GeoGebra command names in storyboard notes unless they are documented in the active syntax manual; describe unsupported effects generically instead.\n";

    private static final String MANIM_RULES =
            "Manim-specific storyboard rules:\n"
                    + SystemPrompts.MANIM_MOTION_AND_PACING_RULES
                    + SystemPrompts.MANIM_TEXT_AND_READABILITY_RULES
                    + SystemPrompts.MANIM_NAMING_RULES
                    + "- Every learner-visible Manim object must be explicitly represented in `entering_objects` or `persistent_objects`; do not hide visible labels inside another object's prose description.\n"
                    + "- If a point, marker, label, counter, or helper must visibly follow another object, create a separate object and describe the attachment with `behavior`, `anchor_id`, and `dependency_note`.\n"
                    + "- For moving points or markers, create a separate label object with `behavior = follows_anchor` so the label tracks the moving object.\n"
                    + "- Manim does not auto-label any object. For every object (points, lines, angles, arcs, etc.) whose name or value must appear on screen, explicitly declare a companion `kind: text` label in the same scene's `entering_objects`; attach it with `behavior = follows_anchor` and `anchor_id` pointing to the parent object's id. Never assume a label will appear automatically.\n"
                    + "- Use `screen_overlay_plan` only for true viewport-fixed explanatory overlays, not as a vague place to hide layout conflicts.\n"
                    + "- Once a color is assigned to a concept, it keeps that meaning across the entire storyboard. Record color-to-concept assignments in `global_visual_rules`.\n";

    private static final String OUTPUT_FORMAT =
            "Output format:\n"
                    + "Strict JSON syntax requirements:\n"
                    + "- Return one JSON object only. No markdown fence and no prose before or after it.\n"
                    + "- Use double quotes for all keys and all string values.\n"
                    + "- Categorical/string fields must be quoted everywhere, including style properties and action metadata.\n"
                    + "- Allowed unquoted literals are only numbers, true, false, and null.\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"hook\": \"string, opening hook that creates curiosity and frames the visualization\",\n"
                    + "  \"summary\": \"string, short overview of the full storyboard arc and teaching intent\",\n"
                    + "  \"continuity_plan\": \"string, how object identities, anchors, and layout stay stable across scenes\",\n"
                    + "  \"global_visual_rules\": [\"string, global staging rule that should hold across the whole presentation\"],\n"
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
                    + "          \"id\": \"string, stable visual identity for continuity and transforms; keep ids concise and non-redundant since `kind` carries the type; follow only the active backend's naming rules\",\n"
                    + "          \"kind\": \"string, object category such as text|equation|axes|point|graph|label|region|helper; do not repeat this type inside `id`\",\n"
                    + "          \"content\": \"string, mathematical or visual content shown by the object. If this text references other storyboard objects, mention those objects by id only and do not repeat their kind, for example `angle between AP and l at P`. If the object must be visible to the learner, declare it explicitly rather than implying it through another object's prose.\",\n"
                    + "          \"placement\": \"string, explicit initial placement or layout intent relative to the frame or existing anchors; do not use it as the only place to encode hard geometry\",\n"
                    + "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"string, visual role such as text|background|border|glow|badge|emphasis\",\n"
                    + "              \"type\": \"string, backend-neutral implementation hint such as math_text|plain_text|background_box|border_box|highlight_ring\",\n"
                    + "              \"properties\": {\n"
                    + "                \"key\": \"value, backend-relevant style property such as color, font_size, fill_opacity, padding, corner_radius, stroke_width, line_style, or label_visible\"\n"
                    + "              }\n"
                    + "            }\n"
                    + "          ],\n"
                    + "          \"source_node\": \"string, originating step or node when relevant\",\n"
                    + "          \"behavior\": \"string, dependency role such as static|follows_anchor|derived|fixed_overlay; this does not by itself mean fixed or movable, and `fixed_overlay` is mainly for explanatory text or UI-like overlays rather than native geometry\",\n"
                    + "          \"anchor_id\": \"string, id of the object this one should stay attached to when relevant\",\n"
                    + "          \"dependency_note\": \"string, short note describing what source objects define this object or what it should keep following/updating with; do not omit visible attachment logic\",\n"
                    + "          \"constraint_note\": \"string, hard local geometric rule for this object, such as 'reflection of B across line l', 'lies on l', or 'intersection of AB'' with l'\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"persistent_objects\": [\n"
                    + "        \"string, id of an object that remains visible from previous scenes\"\n"
                    + "      ],\n"
                    + "      \"exiting_objects\": [\n"
                    + "        \"string, id of an object removed in this scene\"\n"
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
                    + "  \"hook\": \"Start with a concrete question or visual hook that makes the viewer want the next step.\",\n"
                    + "  \"summary\": \"Briefly describe the teaching arc from setup to conclusion.\",\n"
                    + "  \"continuity_plan\": \"Explain how object identities and anchors stay stable across scenes.\",\n"
                    + "  \"global_visual_rules\": [\n"
                    + "    \"Keep major content inside the safe frame.\",\n"
                    + "    \"Prefer transforms and persistent anchors over redraws.\"\n"
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
                    + "          \"kind\": \"line\",\n"
                    + "          \"content\": \"Number line from -2 to 6 with integer ticks\",\n"
                    + "          \"placement\": \"Centered horizontally at y=0\",\n"
                    + "          \"source_node\": \"problem_setup\",\n"
                    + "          \"behavior\": \"static\",\n"
                    + "          \"anchor_id\": \"\",\n"
                    + "          \"dependency_note\": \"independent baseline\",\n"
                    + "          \"constraint_note\": \"fixed baseline\"\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"id\": \"P\",\n"
                    + "          \"kind\": \"point\",\n"
                    + "          \"content\": \"Moving point on numberLine\",\n"
                    + "          \"placement\": \"Start near (2,0) on numberLine\",\n"
                    + "          \"source_node\": \"problem_setup\",\n"
                    + "          \"behavior\": \"derived\",\n"
                    + "          \"anchor_id\": \"numberLine\",\n"
                    + "          \"dependency_note\": \"point on numberLine; draggable along it\",\n"
                    + "          \"constraint_note\": \"lies on numberLine\"\n"
                    + "        },\n"
                    + "        {\n"
                    + "          \"id\": \"formulaCard\",\n"
                    + "          \"kind\": \"text_card\",\n"
                    + "          \"content\": \"min = 2 for x in [1,3]\",\n"
                    + "          \"placement\": \"Centered above the main diagram\",\n"
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
                    + "          ],\n"
                    + "          \"source_node\": \"minimum_reveal\",\n"
                    + "          \"behavior\": \"fixed_overlay\",\n"
                    + "          \"anchor_id\": \"\",\n"
                    + "          \"dependency_note\": \"\",\n"
                    + "          \"constraint_note\": \"\"\n"
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
                "Stage 1c / Narrative Composition",
                "Storyboard composition",
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

    public static String conceptUserPrompt(String targetConcept, String stepContext) {
        return String.format(
                "Target concept: %s\n\nStep progression chain:\n%s\n\nProduce a continuity-safe storyboard JSON, not free text.",
                targetConcept, stepContext);
    }

    public static String problemUserPrompt(String problemStatement,
                                           String solvingContext,
                                           int targetSceneCount) {
        return String.format(
                "Math problem to solve: %s\n\nOrdered solution-step graph context:\n%s\n\n"
                        + "Write the visualization as a structured teaching storyboard, not as a plain written solution.\n"
                        + "Start by establishing the problem situation, then move through the key observation, insight, and solution beats in order.\n"
                        + "Target about %d scenes total, but merge nodes whenever that improves focus and continuity.\n"
                        + "Return storyboard JSON only.",
                problemStatement, solvingContext, targetSceneCount);
    }

    public static String storyboardCodegenPrompt(String targetConcept, Storyboard storyboard) {
        return storyboardCodegenPrompt(targetConcept, storyboard, "manim");
    }

    public static String storyboardCodegenPrompt(String targetConcept,
                                                 Storyboard storyboard,
                                                 String outputTarget) {
        return storyboardCodegenPrompt(
                targetConcept,
                StoryboardJsonBuilder.buildForCodegen(storyboard),
                outputTarget);
    }

    public static String storyboardCodegenPrompt(String targetConcept, String storyboardJson) {
        return storyboardCodegenPrompt(targetConcept, storyboardJson, "manim");
    }

    public static String storyboardCodegenPrompt(String targetConcept,
                                                 String storyboardJson,
                                                 String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return String.format(
                    "Target concept: %s\n\n"
                            + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                            + "Remember: Return ONLY the single GeoGebra code block. No explanation.",
                    targetConcept, storyboardJson);
        }
        return String.format(
                "Target concept: %s\n\n"
                        + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                targetConcept, storyboardJson);
    }
}
