package com.automanim.prompt;

import com.automanim.model.Narrative.Storyboard;

/**
 * Prompts for Stage 1c: narrative composition.
 */
public final class NarrativePrompts {

    private static final String SYSTEM =
            "You are a STEM narrative designer writing a structured storyboard for a math teaching visualization.\n"
                    + "Write a scene-by-scene storyboard that functions as a visual presentation plan rather than a written solution.\n"
                    + "Begin with a clear hook, introduce foundations before advanced content, and keep the storyboard continuity-safe.\n\n"
                    + "Layout rules:\n"
                    + "- Frame is 16:9 with " + SystemPrompts.LAYOUT_FRAME_RULES.toLowerCase().replace("keep important content within", "important content kept inside").replace("usually keep each step to", "keep simultaneous main visual elements around").replace(".\n", "\n- ").trim() + "\n"
                    + "- Place formulas near edges, not over the main geometry\n"
                    + "- Keep the diagram stable across scenes and change only the necessary layer\n"
                    + "- " + SystemPrompts.STORYBOARD_FIELD_GUIDE.replace("\n- ", "\n- ").replace("How to interpret the storyboard fields:\n", "Field responsibilities: ").trim() + "\n"
                    + "- If an object is movable but constrained, keep `behavior` for dependency semantics and encode the motion/path/range constraint explicitly in `geometry_constraints`, `constraint_note`, `dependency_note`, `placement`, or `notes_for_codegen`\n"
                    + "- When an object depends on another object's position, encode that dependency explicitly with `behavior`, `anchor_id`, and `dependency_note`\n"
                    + "- When a geometric relationship must survive later layout fixes, record it explicitly in `geometry_constraints` and in object-level `constraint_note`\n"
                    + "- Treat relationships such as symmetry, reflection, equal length, equal angle, collinearity, intersection, perpendicularity, and shared-center motion as hard constraints, not optional style notes\n"
                    + "- If a layout risks overflow, prefer planning a smaller or recentered whole construction rather than placing mathematically linked points independently near the edges\n\n"
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed in frame\n\n"
                    + "Narrative rules:\n"
                    + "- Narrative must not be constrained by a fixed word count\n"
                    + "- Use enrichment fields only when they sharpen the explanation\n"
                    + "- If the target is a problem, every scene must directly advance the solution\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "- Keep object ids concise and non-redundant: prefer `A`, `B`, `P`, `river` over ids like `pointA`, `labelA`, or `line_river`, since `kind` already carries the type. See the backend-specific naming rules below for allowed id conventions.\n"
                    + "- Reuse the exact same concise ids consistently in `anchor_id`, `persistent_objects`, `exiting_objects`, and `actions.targets`\n"
                    + "- When any field inside `entering_objects` refers to another object, especially `content`, refer to that object by id only. Do not restate its kind there.\n"
                    + "- For example, write `angle between AP and l at P`, not `angle between segment AP and line l at point P`.\n"
                    + "- Prefer structured `style` arrays over vague prose. Each style entry should describe one visual layer or role, such as text, background, border, glow, or emphasis.\n"
                    + "- Do not use a free-text `instructions` field inside style entries. Encode visual intent directly in `properties` using concrete keys and values.\n"
                    + "- For text cards, formulas with badges, boxed labels, counters, or callouts, encode separate text and background layers as separate entries inside `style`.\n"
                    + "- Only include `style` when it adds meaningful rendering properties; omit it for visually plain objects.\n"
                    + "- Prefer restyling an existing object (color, thickness, dash style) over creating a duplicate on the same endpoints; this keeps the construction clean and avoids overlapping objects\n"
                    + "- Each scene shows only the elements needed for its teaching goal; when a temporary comparison element (test point, alternate path) has served its purpose, include it in `exiting_objects` of the current or next scene\n"
                    + "- " + SystemPrompts.HIGH_CONTRAST_COLOR_RULES + "\n"
                    + "Output format:\n"
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
                    + "      \"goal\": \"string, what the scene must accomplish for understanding or solution progress\",\n"
                    + "      \"narration\": \"string, concise voiceover text for this scene only\",\n"
                    + "      \"duration_seconds\": \"integer, approximate runtime for pacing\",\n"
                    + "      \"scene_mode\": \"string, 2d by default or 3d only when depth is essential\",\n"
                    + "      \"camera_anchor\": \"string, main camera focus region or anchor object\",\n"
                    + "      \"camera_plan\": \"string, how the camera behaves in this scene\",\n"
                    + "      \"layout_goal\": \"string, intended screen composition and relative placement of major elements\",\n"
                    + "      \"safe_area_plan\": \"string, how important content stays readable and inside the safe frame\",\n"
                    + "      \"screen_overlay_plan\": \"string, what text or formulas stay fixed in screen space\",\n"
                    + "      \"geometry_constraints\": [\"string, hard geometric invariants that downstream codegen and fix stages must preserve\"],\n"
                    + "      \"step_refs\": [\"string, referenced knowledge-graph step or solving beat covered by this scene\"],\n"
                    + "      \"entering_objects\": [\n"
                    + "        {\n"
                    + "          \"id\": \"string, stable visual identity for continuity and transforms; use concise identifiers, e.g. `A`, `P`, `river`; keep ids non-redundant since `kind` carries the type; see backend-specific naming rules for allowed conventions\",\n"
                    + "          \"kind\": \"string, object category such as text|equation|axes|point|graph|label|region|helper; do not repeat this type inside `id`\",\n"
                    + "          \"content\": \"string, mathematical or visual content shown by the object. If this text references other storyboard objects, mention those objects by id only and do not repeat their kind, for example `angle between AP and l at P`\",\n"
                    + "          \"placement\": \"string, explicit initial placement or layout intent relative to the frame or existing anchors; do not use it as the only place to encode hard geometry\",\n"
                    + "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"string, visual role such as text|background|border|glow|badge|emphasis\",\n"
                    + "              \"type\": \"string, implementation hint such as MathTex|Tex|Text|SurroundingRectangle|RoundedRectangle|BackgroundRectangle\",\n"
                    + "              \"properties\": {\n"
                    + "                \"key\": \"value, backend-relevant style property such as color, font_size, fill_opacity, buff, corner_radius, stroke_width, line_style, or label_visible\"\n"
                    + "              }\n"
                    + "            }\n"
                    + "          ],\n"
                    + "          \"source_node\": \"string, originating step or node when relevant\",\n"
                    + "          \"behavior\": \"string, dependency role such as static|follows_anchor|derived|fixed_overlay; this does not by itself mean fixed or movable\",\n"
                    + "          \"anchor_id\": \"string, id of the object this one should stay attached to when relevant\",\n"
                    + "          \"dependency_note\": \"string, short note describing what source objects define this object or what it should keep following/updating with\",\n"
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
                    + "          \"type\": \"string, action category such as create|write|transform|highlight|move|fade_out|camera\",\n"
                    + "          \"targets\": [\n"
                    + "            \"string, object id mainly affected by the action\"\n"
                    + "          ],\n"
                    + "          \"description\": \"string, precise visual action intent and visible change\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"notes_for_codegen\": [\n"
                    + "        \"string, implementation hint that helps downstream generation preserve intent\"\n"
                    + "      ]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n"
                    + "\n"
                    + "Example output:\n"
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
                    + "          \"id\": \"formula_card\",\n"
                    + "          \"kind\": \"text_card\",\n"
                    + "          \"content\": \"min = 2 for x in [1,3]\",\n"
                    + "          \"placement\": \"Centered above axes at (2,2)\",\n"
                    + "          \"style\": [\n"
                    + "            {\n"
                    + "              \"role\": \"text\",\n"
                    + "              \"type\": \"MathTex\",\n"
                    + "              \"properties\": {\n"
                    + "                \"color\": \"BLACK\",\n"
                    + "                \"font_size\": 30,\n"
                    + "                \"z_index\": 2\n"
                    + "              }\n"
                    + "            },\n"
                    + "            {\n"
                    + "              \"role\": \"background\",\n"
                    + "              \"type\": \"SurroundingRectangle\",\n"
                    + "              \"properties\": {\n"
                    + "                \"fill_color\": \"WHITE\",\n"
                    + "                \"fill_opacity\": 1,\n"
                    + "                \"stroke_color\": \"WHITE\",\n"
                    + "                \"stroke_width\": 1,\n"
                    + "                \"corner_radius\": 0.2,\n"
                    + "                \"buff\": 0.2\n"
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
                    + "          \"targets\": [\"numberLine\", \"P\", \"formula_card\"],\n"
                    + "          \"description\": \"Draw the main diagram, place the moving point, and reveal the conclusion card.\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"notes_for_codegen\": [\n"
                    + "        \"Reuse numberLine and P in later scenes instead of recreating them.\",\n"
                    + "        \"Only include `style` on objects that need non-default rendering properties.\"\n"
                    + "      ]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n\n"
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
                true
        ) + "Output target backend: " + outputTarget + ".\n"
                + "Keep the storyboard reusable, but make it practical for this backend.\n";
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            prompt += "\nGeoGebra-specific storyboard rules:\n"
                    + "- Prefer native GeoGebra object labels over separate `label` or `text` objects for named geometry.\n"
                    + "- This applies broadly to points, lines, segments, rays, circles, polygons, angles, and other geometric objects that already have a natural object name.\n"
                    + "- If the visible text is simply the object's own name or symbol, keep it as the object's native label and do not create a separate storyboard object for it.\n"
                    + "- Create separate `label` or `text` objects only for overlays, formulas, counters, captions, explanatory annotations, or text that is not the object's own native name.\n"
                    + "- Avoid redundant pairs such as `A` plus `aLabel`, `lineL` plus `labelL`, or `circleO` plus `labelO` unless the extra text is semantically different from the object's native label.\n"
                    + "- Follow GeoGebra naming conventions for object ids: point ids must start with an uppercase letter (e.g. `A`, `P_1`), vector ids with a lowercase letter (e.g. `v`, `u`); use `_` for subscripts (`P_1`, `s_{AB}`) and `'` for primes (`B'`). Do not use GeoGebra reserved names (`x`, `y`, `z`, `e`, `i`, `sin`, `cos`, `log`, etc.) as object ids.\n"
                    + "- Translate ASCII-spelled ids to GeoGebra-native math names: `Bprime` → `B'`, `ABprime` → `AB'`, `Popt` → `P_{opt}`, `P1` → `P_1`. If the storyboard already uses native names like `B'`, keep them verbatim.\n"
                    + "- Use style changes (color, line thickness, dash style) on existing objects rather than creating visual duplicates on the same endpoints. GeoGebra objects persist globally, so every redundant object adds permanent clutter.\n"
                    + "- For angle markers, use `Angle(B, A, C)` with `SetFilling` for filled sectors. Use `CircularArc` only for decorative arcs not associated with angle measurement.\n";
        } else {
            prompt += "\nManim-specific storyboard rules:\n"
                    + "- Use ASCII-only camelCase or snake_case identifiers for object ids: `aLabel`, `numberLine`, `formula_card`. These become Python variable names in downstream code generation.\n"
                    + "- Do not use Python reserved words (`class`, `def`, `lambda`, `for`, `if`, `in`, `is`, `not`, `None`, `True`, `False`, etc.) as object ids.\n"
                    + "- For moving points or markers, create a separate label object with `behavior = follows_anchor` so the label tracks the moving object.\n";
        }
        prompt += "\n"
                + SYSTEM;
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
                        + "Write the visualization as a structured problem-solving storyboard, not as a plain written solution.\n"
                        + "Start by establishing the problem situation, then move through the key solving beats in order.\n"
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
                            + SystemPrompts.STORYBOARD_CODEGEN_INTRO_GEOGEBRA + "\n"
                            + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                            + "Remember: Return ONLY the single GeoGebra code block. No explanation.",
                    targetConcept, storyboardJson);
        }
        return String.format(
                "Target concept: %s\n\n"
                        + SystemPrompts.STORYBOARD_CODEGEN_INTRO_MANIM + "\n"
                        + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                targetConcept, storyboardJson);
    }
}