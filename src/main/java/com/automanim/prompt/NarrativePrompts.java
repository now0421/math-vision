package com.automanim.prompt;

import com.automanim.model.Narrative.Storyboard;

/**
 * Prompts for Stage 1c: narrative composition.
 */
public final class NarrativePrompts {

    private static final String SYSTEM =
            "You are a STEM narrative designer writing a structured storyboard for a math teaching visualization.\n"
                    + "Write a scene-by-scene storyboard that functions as a visual presentation plan rather than a written solution.\n"
                    + "Begin with a clear hook, introduce foundations before advanced content, and keep the storyboard continuity-safe.\n"
                    + "\n"
                    + "Layout rules:\n"
                    + "- Frame is 16:9 with important content kept inside x[-7,7], y[-4,4]\n"
                    + "- Leave about 1 unit margin from edges\n"
                    + "- Keep simultaneous main visual elements around 6 to 8\n"
                    + "- Place formulas near edges, not over the main geometry\n"
                    + "- Keep the diagram stable across scenes and change only the necessary layer\n"
                    + "- When an object depends on another object's position, encode that dependency explicitly with `behavior`, `anchor_id`, and `dependency_note`\n"
                    + "- When a geometric relationship must survive later layout fixes, record it explicitly in `geometry_constraints` and in object-level `constraint_note`\n"
                    + "- Treat relationships such as symmetry, reflection, equal length, equal angle, collinearity, intersection, perpendicularity, and shared-center motion as hard constraints, not optional style notes\n"
                    + "- If a layout risks overflow, prefer planning a smaller or recentered whole construction rather than placing mathematically linked points independently near the edges\n"
                    + "\n"
                    + "3D rules:\n"
                    + "- Use `scene_mode = 3d` only when depth is genuinely needed\n"
                    + "- Include explicit `camera_plan`\n"
                    + "- Use `screen_overlay_plan` when text must stay fixed in frame\n"
                    + "\n"
                    + "Narrative rules:\n"
                    + "- Narrative must not be constrained by a fixed word count\n"
                    + "- Use enrichment fields only when they sharpen the explanation\n"
                    + "- If the target is a problem, every scene must directly advance the solution\n"
                    + "- Prefer 3 to 5 strong scenes for problem-solving unless more are truly needed\n"
                    + "- If a point or marker moves, its label should usually be a separate object with `behavior = follows_anchor`\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"hook\": \"string, opening hook that creates curiosity and frames the visualization\",\n"
                    + "  \"summary\": \"string, short overview of the full storyboard arc and teaching intent\",\n"
                    + "  \"continuity_plan\": \"string, how object identities, anchors, and layout stay stable across scenes\",\n"
                    + "  \"global_visual_rules\": [\n"
                    + "    \"string, global staging rule that should hold across the whole presentation\"\n"
                    + "  ],\n"
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
                    + "      \"geometry_constraints\": [\n"
                    + "        \"string, hard geometric invariants that downstream codegen and fix stages must preserve, such as reflections, equal distances, collinearity, or intersection definitions\"\n"
                    + "      ],\n"
                    + "      \"step_refs\": [\n"
                    + "        \"string, referenced knowledge-graph step or solving beat covered by this scene\"\n"
                    + "      ],\n"
                    + "      \"entering_objects\": [\n"
                    + "        {\n"
                    + "          \"id\": \"string, stable visual identity for continuity and transforms\",\n"
                    + "          \"kind\": \"string, object category such as text|equation|axes|point|graph|label|region|helper\",\n"
                    + "          \"content\": \"string, mathematical or visual content shown by the object\",\n"
                    + "          \"placement\": \"string, explicit placement relative to the frame or existing anchors\",\n"
                    + "          \"style\": \"string, optional style or emphasis instructions\",\n"
                    + "          \"source_node\": \"string, originating step or node when relevant\",\n"
                    + "          \"behavior\": \"string, object behavior such as static|follows_anchor|derived|fixed_overlay\",\n"
                    + "          \"anchor_id\": \"string, id of the object this one should stay attached to when relevant\",\n"
                    + "          \"dependency_note\": \"string, short implementation note describing dynamic attachment or derivation\",\n"
                    + "          \"constraint_note\": \"string, hard local geometric rule for this object, such as 'reflection of B across line l' or 'intersection of AB'' with l'\"\n"
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
                    + "          \"id\": \"main_diagram\",\n"
                    + "          \"kind\": \"diagram\",\n"
                    + "          \"content\": \"The main geometry or visual setup for the scene\",\n"
                    + "          \"placement\": \"Centered slightly left of frame center\",\n"
                    + "          \"style\": \"Primary visual anchor\",\n"
                    + "          \"source_node\": \"problem_setup\",\n"
                    + "          \"behavior\": \"static\",\n"
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
                    + "          \"targets\": [\"main_diagram\"],\n"
                    + "          \"description\": \"Draw the main diagram and hold focus on the givens.\"\n"
                    + "        }\n"
                    + "      ],\n"
                    + "      \"notes_for_codegen\": [\n"
                    + "        \"Reuse main_diagram in later scenes instead of recreating it.\"\n"
                    + "      ]\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n"
                    + "\n"
                    + "If tools are available, call them.\n"
                    + "Return JSON only. Do not wrap it in markdown.";

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
                + "Keep the storyboard reusable, but make it practical for this backend.\n\n"
                + SYSTEM;
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
                            + "Use the following compact storyboard JSON as the source of truth for GeoGebra construction order, object identity, continuity, and teaching progression.\n"
                            + "- Keep the same object identities stable across steps.\n"
                            + "- Convert `actions` into construction order, visibility changes, highlights, or helper toggles rather than literal animation.\n"
                            + "- Preserve `geometry_constraints`, `behavior`, `anchor_id`, `dependency_note`, and `constraint_note` through dependency-safe GeoGebra commands.\n"
                            + "- Choose readable coordinates and label placement that respect `layout_goal`, `placement`, and `safe_area_plan`.\n\n"
                            + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                            + "Remember: Return ONLY the single GeoGebra code block. No explanation.",
                    targetConcept, storyboardJson);
        }
        return String.format(
                "Target concept: %s\n\n"
                        + "Use the following compact storyboard JSON as the source of truth for staging, object identity, continuity, and scene execution.\n"
                        + "- Treat every object id as a stable visual identity.\n"
                        + "- If an id persists, keep or transform the same mobject instead of redrawing it.\n"
                        + "- If a scene uses `scene_mode = 3d`, use `ThreeDScene`, follow `camera_plan`, and judge layout in projected screen space.\n"
                        + "- Use `screen_overlay_plan` with `add_fixed_in_frame_mobjects` for fixed explanatory text.\n"
                        + "- Respect `safe_area_plan` and dynamic attachment for labels on moving objects.\n"
                        + "- Read `behavior`, `anchor_id`, and `dependency_note` literally: if an object follows a moving anchor, implement it with `always_redraw(...)` or an updater.\n"
                        + "- Treat `geometry_constraints` and `constraint_note` as hard invariants. If the frame is tight, preserve the construction and recenter/scale the whole constrained group instead of breaking the math.\n\n"
                        + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                targetConcept, storyboardJson);
    }
}
