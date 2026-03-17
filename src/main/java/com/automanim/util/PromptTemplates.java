package com.automanim.util;

import java.util.Collections;
import java.util.List;

public final class PromptTemplates {

    private PromptTemplates() {}

    private static final String WORKFLOW_OVERVIEW =
            "Stage 0 Exploration -> Stage 1a Mathematical Enrichment -> Stage 1b Visual Design"
            + " -> Stage 1c Narrative Composition -> Stage 2 Code Generation"
            + " -> Stage 3 Code Evaluation -> Stage 4 Render Fix";

    private static String sanitizePromptText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String buildWorkflowSystemPrefix(String stageLabel,
                                                    String substepLabel,
                                                    String targetTitle,
                                                    String targetDescription) {
        return "You are working inside a multi-stage Manim animation generation workflow.\n"
                + "Current workflow stage: " + sanitizePromptText(stageLabel, "Unknown stage") + "\n"
                + "Current substep: " + sanitizePromptText(substepLabel, "Unknown substep") + "\n"
                + "Overall workflow: " + WORKFLOW_OVERVIEW + "\n"
                + "Final animation target: " + sanitizePromptText(targetTitle, "Unknown target") + "\n"
                + "Final target description: "
                + sanitizePromptText(targetDescription, "No explicit target description is available yet.")
                + "\n"
                + "Keep the full target in mind, but perform only the responsibility of the current substep.\n\n";
    }

    public static String workflowTargetDescription(String targetConcept,
                                                   String rootConcept,
                                                   String rootDescription,
                                                   boolean problemMode) {
        String safeTarget = sanitizePromptText(targetConcept, "Unknown target");
        String safeRootConcept = sanitizePromptText(rootConcept, safeTarget);
        String safeRootDescription = sanitizePromptText(rootDescription, "");

        if (problemMode) {
            if (!safeRootDescription.isEmpty()) {
                return String.format(
                        "Solve the math problem \"%s\" through a coherent Manim animation. The solution"
                                + " should culminate in the final conclusion \"%s\": %s",
                        safeTarget, safeRootConcept, safeRootDescription);
            }
            return String.format(
                    "Solve the math problem \"%s\" through a coherent Manim animation that reaches"
                            + " the final conclusion \"%s\".",
                    safeTarget, safeRootConcept);
        }

        if (!safeRootDescription.isEmpty()) {
            return safeRootDescription;
        }
        return String.format(
                "Explain the concept \"%s\" through a coherent Manim animation built from the"
                        + " necessary prerequisites up to the final idea.",
                safeTarget);
    }

    public static String foundationCheckSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Foundation sufficiency check",
                targetTitle,
                targetDescription
        ) + FOUNDATION_CHECK_SYSTEM;
    }

    public static String prerequisitesSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Direct prerequisite extraction",
                targetTitle,
                targetDescription
        ) + PREREQUISITES_SYSTEM;
    }

    public static String inputModeClassifierSystemPrompt(String inputText) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Input mode classification",
                inputText,
                "Decide whether this input should follow the concept-explanation workflow or the"
                        + " problem-solving workflow."
        ) + INPUT_MODE_CLASSIFIER_SYSTEM;
    }

    public static String problemStepGraphSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Problem solution-step graph planning",
                targetTitle,
                targetDescription
        ) + PROBLEM_STEP_GRAPH_SYSTEM;
    }

    public static String mathEnrichmentSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 1a / Mathematical Enrichment",
                "Mathematical content enrichment",
                targetTitle,
                targetDescription
        ) + MATH_ENRICHMENT_SYSTEM;
    }

    public static String visualDesignSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 1b / Visual Design",
                "Scene visual design",
                targetTitle,
                targetDescription
        ) + VISUAL_DESIGN_SYSTEM;
    }

    public static String narrativeSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 1c / Narrative Composition",
                "Storyboard composition",
                targetTitle,
                targetDescription
        ) + NARRATIVE_SYSTEM;
    }

    public static String codeGenerationSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 2 / Code Generation",
                "Generate executable Manim code",
                targetTitle,
                targetDescription
        ) + CODE_GENERATION_SYSTEM;
    }

    public static String codeEvaluationSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 3 / Code Evaluation",
                "Review code for layout, continuity, pacing, and clutter risk",
                targetTitle,
                targetDescription
        ) + CODE_EVALUATION_SYSTEM;
    }

    public static String codeRevisionSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 3 / Code Evaluation",
                "Revise Manim code after code evaluation before render",
                targetTitle,
                targetDescription
        ) + CODE_REVISION_SYSTEM;
    }

    public static String renderFixSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 4 / Render Fix",
                "Repair Manim code after render failure",
                targetTitle,
                targetDescription
        ) + RENDER_FIX_SYSTEM;
    }

    // =====================================================================
    // Stage 0: Exploration
    // =====================================================================

    public static final String FOUNDATION_CHECK_SYSTEM =
            "You are a curriculum design expert building a prerequisite DAG for a Manim"
            + " teaching animation.\n"
            + "\n"
            + "Workflow context:\n"
            + "- The DAG will be topologically traversed to produce a sequence of animated scenes.\n"
            + "- Each node becomes one scene; foundation (leaf) nodes become the simplest opening"
            + " scenes, so they must be truly self-explanatory.\n"
            + "\n"
            + "Decide whether the given concept is already basic enough for an ordinary"
            + " middle-school student to understand directly, while still being clearly relevant"
            + " to the final teaching goal.\n"
            + "\n"
            + "Rules:\n"
            + "1. Judge from a middle-school student's perspective.\n"
            + "2. The concept must be clearly useful for the final teaching goal.\n"
            + "3. If it still bundles multiple sub-ideas, steps, or layers, answer no.\n"
            + "4. If it requires advanced abstraction, formal derivation, or significant prior"
            + " knowledge, answer no.\n"
            + "5. If it would overlap with nearby concepts in the graph, answer no.\n"
            + "6. Bias toward no when uncertain.\n"
            + "\n"
            + "If tools are available, call them.\n"
            + "Otherwise return only a JSON object with the same shape as the tool output.\n"
            + "- Required field: \"is_foundation\" (boolean)\n"
            + "- Optional field: \"reason\" (short string)\n"
            + "- Do not return yes/no prose, markdown, or any extra text outside the JSON object.\n"
            + "\n"
            + "Example output:\n"
            + "{\"is_foundation\": false, \"reason\": \"The concept still bundles multiple"
            + " sub-ideas and should be decomposed before it becomes a leaf scene.\"}";

    public static final String PREREQUISITES_SYSTEM =
            "You are a curriculum design expert building a prerequisite DAG for a Manim"
            + " teaching animation.\n"
            + "\n"
            + "Workflow context:\n"
            + "- The DAG is topologically traversed to generate one animated scene per node.\n"
            + "- Manim excels at dynamic, continuous motion: moving points along curves,"
            + " morphing shapes, tracing trajectories, smooth parameter sweeps, and"
            + " step-by-step geometric constructions.\n"
            + "- For each prerequisite, you must also write a brief \"description\" explaining how"
            + " this concept should be presented in animation, leveraging Manim's motion strengths.\n"
            + "\n"
            + "Rules:\n"
            + "1. Return only truly necessary prerequisites, not merely helpful background.\n"
            + "2. Keep them directly relevant to the final teaching goal.\n"
            + "3. Avoid overly broad, tangential, or generic topics.\n"
            + "4. Each prerequisite should express one clear, atomic concept.\n"
            + "5. Avoid synonyms, near-duplicates, and parent-child duplication.\n"
            + "6. Prefer simpler concepts tightly connected to the goal.\n"
            + "7. Replace overly advanced candidates with more basic but relevant ones.\n"
            + "8. Return at most 3 to 5 items, ordered by necessity.\n"
            + "\n"
            + "If tools are available, call them.\n"
            + "Otherwise return a JSON array of objects, each with two fields:\n"
            + "- \"concept\": the concept name\n"
            + "- \"description\": one or two sentences describing how this concept is best shown"
            + " in a Manim animation (e.g. dynamic point motion, shape morphing, parameter"
            + " sweep, coordinate tracing, step-by-step construction)\n"
            + "\n"
            + "Example:\n"
            + "[{\"concept\": \"Unit circle\", \"description\": \"Animate a point tracing the"
            + " unit circle while projecting its x and y coordinates onto the axes in real time"
            + " to reveal sine and cosine.\"}]";

    public static final String INPUT_MODE_CLASSIFIER_SYSTEM =
            "You are a routing classifier for a Manim math-animation workflow.\n"
            + "\n"
            + "Given a single user input, decide the workflow mode:\n"
            + "- concept: a concept, theorem, formula, topic, or idea to explain via animation\n"
            + "- problem: a concrete math problem, exercise, proof, optimization, or question"
            + " to solve step by step\n"
            + "\n"
            + "Choose problem when the input asks for a result, proof, minimization,"
            + " construction, derivation, or contains detailed givens and a target.\n"
            + "Choose concept when the input names a topic or formula to introduce.\n"
            + "\n"
            + "If tools are available, call them.\n"
            + "Otherwise reply with only one word: concept or problem.\n"
            + "\n"
            + "Example tool output:\n"
            + "{\"input_mode\": \"problem\", \"reason\": \"The input is a concrete optimization"
            + " problem with givens and a target to solve.\"}";

    public static final String PROBLEM_STEP_GRAPH_SYSTEM =
            "You are a mathematical problem-solving planner preparing a Manim animation workflow.\n"
            + "\n"
            + "Workflow context:\n"
            + "- The dependency graph will be topologically traversed; each node becomes one"
            + " animated scene.\n"
            + "- Manim excels at dynamic, continuous motion: moving points along paths, morphing"
            + " shapes, tracing trajectories, smooth parameter sweeps, and step-by-step"
            + " geometric constructions.\n"
            + "- For each node, write a \"description\" that explains how this step should be"
            + " animated, leveraging motion and transformation rather than static diagrams.\n"
            + "\n"
            + "Decompose the problem into a compact dependency graph of solving steps.\n"
            + "\n"
            + "Rules:\n"
            + "1. Focus on the actual route to the solution.\n"
            + "2. Each node must be an atomic solving step.\n"
            + "3. Use node_type from: problem, observation, construction, derivation, conclusion.\n"
            + "4. Include exactly one root conclusion node for the final answer, final claim,"
            + " or decisive end state of the solution.\n"
            + "5. Include exactly one separate problem node for the original statement,"
            + " givens, and goal.\n"
            + "6. Set the root conclusion node to min_depth 0. Earlier required steps should"
            + " have larger min_depth values.\n"
            + "7. Prefer 4 to 8 nodes unless the problem truly needs more.\n"
            + "8. Dependencies must point only to earlier required steps.\n"
            + "9. Avoid generic textbook topics such as 'geometry' or 'algebra basics'.\n"
            + "10. Use concise English labels that work well as scene titles.\n"
            + "\n"
            + "Return a JSON object with this exact shape:\n"
            + "{\n"
            + "  \"root_id\": \"final_answer\",\n"
            + "  \"nodes\": [\n"
            + "    {\"id\": \"final_answer\", \"concept\": \"Shortest path occurs when A, P,"
            + " and B' are collinear\", \"description\": \"Reveal the final straight-line"
            + " alignment and state why this gives the minimum path.\","
            + " \"node_type\": \"conclusion\", \"min_depth\": 0, \"is_foundation\": false},\n"
            + "    {\"id\": \"observe_alignment\", \"concept\": \"Transform AP + PB into AP +"
            + " PB' and look for one straight line\", \"description\": \"Move point P along the"
            + " riverbank while updating AP, PB, and PB' to show the special aligned case.\","
            + " \"node_type\": \"observation\", \"min_depth\": 1, \"is_foundation\": false},\n"
            + "    {\"id\": \"problem\", \"concept\": \"Original problem statement\","
            + " \"description\": \"Display the problem text, then animate the given geometric"
            + " figure being drawn step by step.\", \"node_type\": \"problem\","
            + " \"min_depth\": 2, \"is_foundation\": false}\n"
            + "  ],\n"
            + "  \"prerequisite_edges\": {\n"
            + "    \"final_answer\": [\"observe_alignment\"],\n"
            + "    \"observe_alignment\": [\"problem\"]\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "The edge direction: node -> direct dependencies needed before it.\n"
            + "If tools are available, call them.\n"
            + "\n"
            + "Example output must follow the same shape exactly.";

    // =====================================================================
    // Stage 1a: Mathematical Enrichment
    // =====================================================================

    public static final String MATH_ENRICHMENT_SYSTEM =
            "You are a mathematics and physics educator preparing content for a Manim animation.\n"
            + "\n"
            + "The user will provide a concept or problem-step name, node type, depth, and an"
            + " animation description from the planning stage when available. Use that planning"
            + " description as teaching context, but return only the mathematical content that"
            + " genuinely improves teaching quality.\n"
            + "\n"
            + "MathTex / LaTeX rules:\n"
            + "- Use raw LaTeX strings without dollar signs.\n"
            + "- Escape backslashes as needed, for example \\\\frac{a}{b}.\n"
            + "- For multi-line formulas, return each line as a separate array item.\n"
            + "- Wrap non-mathematical text in formulas with \\\\text{}.\n"
            + "\n"
            + "Return a JSON object containing:\n"
            + "- \"equations\": array of key LaTeX formulas\n"
            + "- \"definitions\": object mapping symbols to meanings\n"
            + "- \"interpretation\": short explanation when useful\n"
            + "- \"examples\": optional examples when useful\n"
            + "\n"
            + "Do not pad the response. Simple concepts should get concise output.\n"
            + "\n"
            + "Example output:\n"
            + "{\n"
            + "  \"equations\": [\"a^2 + b^2 = c^2\"],\n"
            + "  \"definitions\": {\"c\": \"the hypotenuse of a right triangle\"},\n"
            + "  \"interpretation\": \"The theorem links the two legs to the hypotenuse.\",\n"
            + "  \"examples\": [\"For a 3-4-5 triangle, 3^2 + 4^2 = 5^2.\"]\n"
            + "}";

    // =====================================================================
    // Stage 1b: Visual Design
    // =====================================================================

    public static final String VISUAL_DESIGN_SYSTEM =
            "You are a visual designer for Manim-based math animations.\n"
            + "\n"
            + "The user will provide concept or problem-step details, node type, prerequisite"
            + " visual context, and the current color palette state. Describe the visual objects,"
            + " color scheme, animation feel, and layout.\n"
            + "\n"
            + "Canvas constraints for a 16:9 frame (roughly 14x8 units):\n"
            + "- Keep important content within x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Leave at least 1 unit of margin from the frame edge.\n"
            + "- A scene should usually contain no more than 6 to 8 main visual elements.\n"
            + "- The layout field must describe concrete spatial placement.\n"
            + "\n"
            + "Return a JSON object containing:\n"
            + "- \"visual_description\"\n"
            + "- \"color_scheme\"\n"
            + "- \"layout\"\n"
            + "- \"animation_description\" when useful\n"
            + "- \"transitions\" when useful\n"
            + "- \"duration\" when useful\n"
            + "- \"color_palette\" as an optional array of Manim color names\n"
            + "\n"
            + "Do not add optional fields unless they are genuinely useful.\n"
            + "\n"
            + "Example output:\n"
            + "{\n"
            + "  \"visual_description\": \"Show a centered triangle with the hypotenuse highlighted"
            + " and a small formula block in the upper-right corner.\",\n"
            + "  \"color_scheme\": \"Use BLUE for the main shape, YELLOW for the highlighted side,"
            + " and WHITE for labels.\",\n"
            + "  \"layout\": \"Keep the triangle centered, place labels close to each vertex,"
            + " and keep the formula in the upper-right safe area.\",\n"
            + "  \"animation_description\": \"Draw the triangle first, then pulse the highlighted"
            + " side before writing the formula.\",\n"
            + "  \"duration\": 8,\n"
            + "  \"color_palette\": [\"BLUE\", \"YELLOW\", \"WHITE\"]\n"
            + "}";

    // =====================================================================
    // Stage 1c: Narrative Composition
    // =====================================================================

    private static final String STORYBOARD_SCHEMA_GUIDE =
            "Return a JSON object with this structure:\n"
            + "{\n"
            + "  \"hook\": \"opening motivation\",\n"
            + "  \"summary\": \"overall teaching arc\",\n"
            + "  \"continuity_plan\": \"how the same layout/objects evolve across scenes\",\n"
            + "  \"global_visual_rules\": [\"global staging rule 1\", \"global staging rule 2\"],\n"
            + "  \"scenes\": [\n"
            + "    {\n"
            + "      \"scene_id\": \"scene_1\",\n"
            + "      \"title\": \"short title\",\n"
            + "      \"goal\": \"what this scene teaches\",\n"
            + "      \"narration\": \"short narration for this beat\",\n"
            + "      \"duration_seconds\": 8,\n"
            + "      \"camera_anchor\": \"main visual anchor or focus region\",\n"
            + "      \"layout_goal\": \"explicit spatial arrangement\",\n"
            + "      \"safe_area_plan\": \"how this scene stays inside x in [-6.5, 6.5] and y in [-3.5, 3.5]\",\n"
            + "      \"concept_refs\": [\"relevant node or concept names\"],\n"
            + "      \"entering_objects\": [\n"
            + "        {\n"
            + "          \"id\": \"formula_main\",\n"
            + "          \"kind\": \"formula|geometry|text|axis|label|highlight\",\n"
            + "          \"content\": \"what appears on screen\",\n"
            + "          \"placement\": \"exact initial position\",\n"
            + "          \"style\": \"color/scale/emphasis\",\n"
            + "          \"source_node\": \"optional source concept\"\n"
            + "        }\n"
            + "      ],\n"
            + "      \"persistent_objects\": [\"object ids that remain into the next scene\"],\n"
            + "      \"exiting_objects\": [\"object ids that must fade out or be removed\"],\n"
            + "      \"actions\": [\n"
            + "        {\n"
            + "          \"order\": 1,\n"
            + "          \"type\": \"create|transform|move|highlight|fade_out|camera_focus\",\n"
            + "          \"targets\": [\"formula_main\"],\n"
            + "          \"description\": \"precise visual action in order\"\n"
            + "        }\n"
            + "      ],\n"
            + "      \"notes_for_codegen\": [\"continuity note\", \"layout note\"]\n"
            + "    }\n"
            + "  ]\n"
            + "}\n"
            + "\n"
            + "Example output:\n"
            + "{\n"
            + "  \"hook\": \"Start with one concrete question about the diagram.\",\n"
            + "  \"summary\": \"Move from setup to key observation to final conclusion.\",\n"
            + "  \"continuity_plan\": \"Keep one stable diagram and update only the necessary"
            + " labels, highlights, and formulas.\",\n"
            + "  \"global_visual_rules\": [\n"
            + "    \"Keep the main geometry near the center.\",\n"
            + "    \"Keep formulas near a corner instead of covering the diagram.\"\n"
            + "  ],\n"
            + "  \"scenes\": [\n"
            + "    {\n"
            + "      \"scene_id\": \"scene_1\",\n"
            + "      \"title\": \"Set Up The Figure\",\n"
            + "      \"goal\": \"Introduce the main objects and the question.\",\n"
            + "      \"narration\": \"Draw the base figure and state what we want to prove.\",\n"
            + "      \"duration_seconds\": 8,\n"
            + "      \"camera_anchor\": \"center\",\n"
            + "      \"layout_goal\": \"Keep the diagram centered and reserve the upper-right"
            + " corner for one short formula.\",\n"
            + "      \"safe_area_plan\": \"Keep the diagram inside the central safe area and leave"
            + " a one-unit margin from all edges.\",\n"
            + "      \"concept_refs\": [\"original problem statement\"],\n"
            + "      \"entering_objects\": [\n"
            + "        {\n"
            + "          \"id\": \"triangle_main\",\n"
            + "          \"kind\": \"geometry\",\n"
            + "          \"content\": \"main triangle\",\n"
            + "          \"placement\": \"center\",\n"
            + "          \"style\": \"blue outline\",\n"
            + "          \"source_node\": \"problem\"\n"
            + "        }\n"
            + "      ],\n"
            + "      \"persistent_objects\": [\"triangle_main\"],\n"
            + "      \"exiting_objects\": [],\n"
            + "      \"actions\": [\n"
            + "        {\n"
            + "          \"order\": 1,\n"
            + "          \"type\": \"create\",\n"
            + "          \"targets\": [\"triangle_main\"],\n"
            + "          \"description\": \"Draw the main triangle and pause briefly for orientation.\"\n"
            + "        }\n"
            + "      ],\n"
            + "      \"notes_for_codegen\": [\n"
            + "        \"Keep labels close to vertices.\",\n"
            + "        \"Do not cover the triangle with text.\"\n"
            + "      ]\n"
            + "    }\n"
            + "  ]\n"
            + "}\n";

    public static final String NARRATIVE_SYSTEM =
            "You are a STEM narrative designer writing a structured storyboard for a Manim animation.\n"
            + "\n"
            + "The user will provide a target concept together with an ordered concept progression"
            + " chain enriched with mathematical and visual context.\n"
            + "\n"
            + "Write a scene-by-scene storyboard that:\n"
            + "- begins with a clear hook or motivation\n"
            + "- explains foundations before advanced content\n"
            + "- preserves provided LaTeX formulas exactly when referenced\n"
            + "- turns every visual change into explicit staging data rather than vague prose\n"
            + "- feels like one connected visual argument, not a stack of unrelated mini-lessons\n"
            + "- keeps a stable diagram/layout whenever possible and changes only the necessary layer\n"
            + "\n"
            + "Canvas constraints for storyboard design:\n"
            + "- Treat the canvas as a 16:9 frame with important content kept inside"
            + " x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Leave about 1 unit of margin from the frame edge.\n"
            + "- Keep the number of simultaneous main visual elements to about 6 to 8.\n"
            + "- Put large formulas near an edge or corner rather than over the main geometry.\n"
            + "- If a layout would overflow, split it into multiple scenes or reduce what is shown.\n"
            + "- Every scene must include `safe_area_plan` explaining how the layout avoids overflow.\n"
            + "\n"
            + "Important selection rule:\n"
            + "- Mathematical enrichment fields such as equations, definitions, interpretations,"
            + " and examples are optional supporting material.\n"
            + "- Use them only when they sharpen the explanation, the proof, or the visual focus.\n"
            + "- It is correct to ignore optional math details that would make the animation"
            + " redundant, overcrowded, or unfocused.\n"
            + "\n"
            + "Storyboard rules:\n"
            + "- Every scene must explicitly state which objects enter, which persist to the next"
            + " scene, and which exit.\n"
            + "- Every object needs a stable object id so code generation can reference it.\n"
            + "- Every placement must be spatially explicit, such as upper-right formula block,"
            + " centered triangle, left axis, or bottom caption.\n"
            + "- Every scene must include ordered actions, not just a general summary.\n"
            + "- Use camera_anchor and layout_goal to prevent accidental layout drift.\n"
            + "- Use safe_area_plan to state the anti-overflow strategy for each scene.\n"
            + "- Prefer 3 to 5 strong scenes for problem-solving workflows unless the concept truly"
            + " needs more.\n"
            + "- Keep narration concise; the storyboard JSON is primarily for staging clarity.\n"
            + "\n"
            + "Problem-solving focus rules:\n"
            + "- If the target is a math problem, every scene must directly advance the solution.\n"
            + "- Do not give secondary facts, historical remarks, or theorem side-quests their own"
            + " standalone scenes unless they are indispensable.\n"
            + "- Merge nearby steps when they serve one reasoning move.\n"
            + "- Keep one stable diagram and evolve it with small changes from scene to scene.\n"
            + "- Auxiliary facts such as equal-angle laws should appear as brief support, not as"
            + " the main headline, unless the whole problem is about that law.\n"
            + "\n"
            + STORYBOARD_SCHEMA_GUIDE
            + "\n"
            + "Return only valid JSON. Do not wrap it in markdown. If tools are available, call them.";

    public static String narrativeUserPrompt(String targetConcept, String conceptContext) {
        return String.format(
                "Target concept: %s\n\nConcept progression chain:\n%s\n\n"
                        + "Produce a continuity-safe storyboard JSON, not free text.",
                targetConcept, conceptContext);
    }

    public static String problemNarrativeUserPrompt(String problemStatement,
                                                    String solvingContext,
                                                    int targetSceneCount) {
        return String.format(
                "Math problem to solve: %s\n\nOrdered solution-step graph context:\n%s\n\n"
                        + "Write the animation as a structured problem-solving storyboard. Start by"
                        + " stating the problem clearly, then move through the key observation/"
                        + " construction/derivation steps in solving order, and end with the final"
                        + " answer and why it is correct or optimal.\n"
                        + "Target about %d scenes total.\n"
                        + "Do not force one scene per node; merge nodes whenever that improves"
                        + " focus and continuity.\n"
                        + "Keep the viewer oriented around one persistent diagram, with only the"
                        + " essential new element introduced in each scene.\n"
                        + "Return storyboard JSON only.",
                problemStatement, solvingContext, targetSceneCount);
    }

    public static String storyboardCodegenPrompt(String targetConcept, String storyboardJson) {
        return String.format(
                "Target concept: %s\n\n"
                        + "Use the following storyboard JSON as the source of truth for staging,\n"
                        + "object identity, continuity, and layout.\n"
                        + "- Treat every object id as a stable visual identity.\n"
                        + "- If an id persists into the next scene, keep or transform the same"
                        + " mobject instead of redrawing it from scratch.\n"
                        + "- Remove only the objects listed in exiting_objects unless a full reset is"
                        + " explicitly required by the storyboard.\n"
                        + "- Respect camera_anchor, layout_goal, and notes_for_codegen when placing"
                        + " formulas, labels, and diagrams.\n"
                        + "- Respect safe_area_plan so content stays inside the storyboard's intended"
                        + " safe frame.\n"
                        + "- Preserve the storyboard's scene order and teaching intent.\n\n"
                        + "Storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                targetConcept, storyboardJson);
    }

    public static String codeReviewUserPrompt(String targetConcept,
                                              String sceneName,
                                              String storyboardJson,
                                              String staticAnalysisJson,
                                              String manimCode) {
        return String.format(
                "Target concept: %s\n"
                        + "Scene class name: %s\n\n"
                        + "Review this Manim code for likely presentation quality problems before render.\n"
                        + "Do NOT judge whether the code can execute. Judge whether it is likely to feel"
                        + " crowded, drift visually, feel discontinuous, or mismatch the storyboard pacing.\n"
                        + "\n"
                        + "Storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Manim code to review:\n```python\n%s\n```\n\n"
                        + "Focus on layout safety, continuity between scenes, pacing versus narration,"
                        + " clutter risk, and likely offscreen placement.\n"
                        + "Return only the structured review output.",
                targetConcept, sceneName, storyboardJson, staticAnalysisJson, manimCode);
    }

    public static String codeRevisionUserPrompt(String targetConcept,
                                                String sceneName,
                                                String storyboardJson,
                                                String staticAnalysisJson,
                                                String reviewJson,
                                                String manimCode) {
        return String.format(
                "Target concept: %s\n"
                + "Scene class name: %s\n\n"
                        + "The current Manim code is not approved for render because it likely has"
                        + " presentation-quality problems, not runtime problems.\n"
                        + "\n"
                        + "Storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Structured code review:\n```json\n%s\n```\n\n"
                        + "Current Manim code:\n```python\n%s\n```\n\n"
                        + "Rewrite the FULL code to improve layout discipline, reduce clutter,"
                        + " preserve continuity with transforms instead of resets, keep recurring"
                        + " anchors stable, and better match scene pacing to narration.\n"
                        + "Do not focus on execution unless it directly affects visual continuity.\n"
                        + "Preserve the scene class name and the teaching goal.\n"
                        + "Return ONLY the full Python code block.",
                targetConcept, sceneName, storyboardJson, staticAnalysisJson, reviewJson, manimCode);
    }

    // =====================================================================
    // Stage 2: Code Generation
    // =====================================================================

    public static final String CODE_GENERATION_SYSTEM =
            "You are an expert Manim Community engineer and Python programmer.\n"
            + "\n"
            + "The user will provide a structured storyboard JSON and a target scene class name."
            + " Generate complete, runnable, maintainable Python code that implements the animation.\n"
            + "\n"
            + "CRITICAL OUTPUT RULES:\n"
            + "1. Return ONE SINGLE ```python ... ``` block containing the FULL CODE.\n"
            + "2. Do NOT provide any explanations, comments, or conversational text before or after the code block.\n"
            + "3. The output must be ONLY the code block.\n"
            + "\n"
            + "Requirements:\n"
            + "- Use `from manim import *`.\n"
            + "- Use `MathTex()` for formulas and `Text()` for plain text.\n"
            + "- Use Manim color constants such as `RED`, `BLUE`, `GREEN`, `YELLOW`.\n"
            + "- Include smooth transitions and sensible waits.\n"
            + "- The code must run with `manim render file.py SceneName`.\n"
            + "\n"
            + "Identifier and source rules:\n"
            + "- All Python identifiers must use ASCII letters, digits, and underscores only.\n"
            + "- This applies to class names, function names, method names, variable names,"
            + " helper names, and parameter names.\n"
            + "- Do not generate Chinese identifiers, pinyin identifiers, mojibake, or any"
            + " non-ASCII identifiers.\n"
            + "- The scene class name must be a valid ASCII PascalCase name such as"
            + " `TriangleAreaScene`.\n"
            + "- Methods should use snake_case names such as `show_intro` and `build_triangle`.\n"
            + "- Displayed text may contain Chinese or other Unicode only inside visual text"
            + " content such as `Text()` or `MathTex(\"\\\\text{...}\")`, never in identifiers.\n"
            + "- The generated source must be clean UTF-8 text.\n"
            + "\n"
            + "Mandatory rules:\n"
            + "1. Scene continuity without global reset\n"
            + "- Do not clear the whole scene between storyboard beats unless the storyboard"
            + " explicitly asks for a full reset.\n"
            + "- Prefer one continuous `construct()` with local variables, or helper methods that"
            + " pass and return a local state object.\n"
            + "- Do not store mobjects on `self` just to reuse them across scene methods.\n"
            + "- Reuse persistent objects with `Transform`, `ReplacementTransform`,"
            + " `FadeTransform`, `FadeIn`, `FadeOut`, and `.animate` updates.\n"
            + "- If the storyboard says an object persists, keep the same visual identity and"
            + " anchor position unless an action explicitly moves it.\n"
            + "\n"
            + "2. No hardcoded MathTex subobject indexing\n"
            + "- Do not use numeric slicing like `eq[0][11:13]`.\n"
            + "- Prefer segmented `MathTex` strings and segment-level indexing.\n"
            + "\n"
            + "3. Valid Python source only\n"
            + "- The code must be valid Python syntax.\n"
            + "- Method definitions must be syntactically correct.\n"
            + "- Do not output broken quotes, brackets, or malformed definitions.\n"
            + "\n"
            + "Layout rules:\n"
            + "- Keep content within x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Prefer `VGroup(...).arrange(...)` for multi-element layouts.\n"
            + "- Use `.scale_to_fit_width()` or `.scale_to_fit_height()` to prevent overflow.\n"
            + "- Keep simultaneous main visual elements roughly within 6 to 8.\n"
            + "- Treat the animation as one connected visual story with a stable coordinate system.\n"
            + "- Reuse the same anchor positions for recurring objects across scene methods.\n"
            + "- When a storyboard scene advances, preserve the same base diagram and change only"
            + " the one or two key layers described in the actions.\n"
            + "- Keep the center of the frame reserved for the main geometry or motion.\n"
            + "- Prefer small corner titles over giant centered headings.\n"
            + "- Keep at most one title line and one formula block visible at a time.\n"
            + "- Title text should usually stay at font size 28 to 36 and occupy no more than"
            + " about 70%% of the frame width.\n"
            + "- Formula blocks should usually occupy no more than about 45%% of the frame width"
            + " and should be placed near the edge, not over the main geometry.\n"
            + "- Use nearby labels for points and segments instead of large explanatory paragraphs.\n"
            + "\n"
            + "Return only Python code inside a ```python ... ``` block.";

    // =====================================================================
    // Stage 3: Code Evaluation
    // =====================================================================

    public static final String CODE_EVALUATION_SYSTEM =
            "You are a senior Manim code reviewer.\n"
            + "\n"
            + "Your job is NOT to debug runtime errors.\n"
            + "Your job is to inspect the code and predict whether the generated animation is likely to look crowded,"
            + " drift around the frame, feel visually discontinuous, or be badly paced against"
            + " the storyboard narration.\n"
            + "\n"
            + "The storyboard JSON is the source of truth.\n"
            + "- Compare the code against the storyboard's object continuity, safe-area plan,"
            + " layout goals, and scene pacing.\n"
            + "- Treat repeated `to_edge` / large `shift` positioning, heavy text stacking,"
            + " many same-time formulas, and overuse of `FadeIn` / `FadeOut` in place of"
            + " transforms as warning signs.\n"
            + "- Penalize code that likely pushes content toward screen edges, redraws"
            + " persistent objects instead of transforming them, or lacks a consistent layout"
            + " strategy such as `arrange`, `next_to`, and stable anchors.\n"
            + "\n"
            + "Return only structured JSON (or equivalent tool output) with these fields:\n"
            + "- `approved_for_render`: boolean\n"
            + "- `layout_score`: integer 1-10 where higher is better\n"
            + "- `continuity_score`: integer 1-10 where higher is better\n"
            + "- `pacing_score`: integer 1-10 where higher is better\n"
            + "- `clutter_risk`: integer 1-10 where higher means more risk\n"
            + "- `likely_offscreen_risk`: integer 1-10 where higher means more risk\n"
            + "- `summary`: short paragraph\n"
            + "- `strengths`: short array\n"
            + "- `blocking_issues`: short array\n"
            + "- `revision_directives`: short array of actionable visual revisions\n"
            + "\n"
            + "Score guidance:\n"
            + "- Layout score should drop when objects are likely near edges or stacked without spacing.\n"
            + "- Continuity score should drop when persistent storyboard objects are redrawn or"
            + " replaced by abrupt fade cycles.\n"
            + "- Pacing score should drop when narration density and scene timing feel mismatched.\n"
            + "- Clutter risk rises when too many text/formula objects are visible together.\n"
            + "- Offscreen risk rises when code heavily depends on repeated edge pushes or large shifts.\n"
            + "\n"
            + "Be calibrated and pragmatic. Only withhold approval when there are clear,"
            + " viewer-visible presentation risks likely to matter in the rendered output."
            + " Do not fail the code for minor imperfections or stylistic variation alone.";

    public static final String CODE_REVISION_SYSTEM =
            "You are a Manim code revision specialist.\n"
            + "\n"
            + "You will receive storyboard JSON, static visual findings, a structured review,"
            + " and the current Manim code.\n"
            + "Rewrite the full code so it is visually safer before render.\n"
            + "\n"
            + "Priorities:\n"
            + "- Reduce clutter by simplifying what is shown at one time.\n"
            + "- Preserve continuity with `Transform`, `ReplacementTransform`,"
            + " `FadeTransform`, and stable anchors.\n"
            + "- Avoid pushing many objects to the frame edge with repeated `to_edge` or large `shift`.\n"
            + "- Use consistent spacing helpers such as `VGroup(...).arrange(...)`, `next_to`,"
            + " aligned edges, and safe-area scaling.\n"
            + "- Match animation beats to the storyboard durations and narration density.\n"
            + "- Keep the main geometry or motion in the center safe area.\n"
            + "\n"
            + "Requirements:\n"
            + "- Return ONE SINGLE ```python ... ``` block containing the FULL corrected code.\n"
            + "- Preserve the storyboard intent and scene class name.\n"
            + "- Preserve ASCII-only identifiers.\n"
            + "- Do not explain the changes. Return only the full code block.";

    // =====================================================================
    // Stage 4: Render Fix
    // =====================================================================

    public static final String RENDER_FIX_SYSTEM =
            "You are a Manim Community debugging expert.\n"
            + "The user will provide failing Manim code together with render error output or"
            + " validation errors.\n"
            + "Fix the code so it renders successfully.\n"
            + "\n"
            + "CRITICAL OUTPUT RULES:\n"
            + "1. Return ONE SINGLE ```python ... ``` block containing the FULL CORRECTED CODE.\n"
            + "2. Do NOT provide any explanations, comments, or conversational text before or after the code block.\n"
            + "3. Do NOT provide partial fixes or snippets.\n"
            + "4. The output must be ONLY the code block.\n"
            + "\n"
            + "Requirements:\n"
            + "- Return the full corrected code, not a patch or partial snippet.\n"
            + "- Preserve the original scene class name and intended animation meaning.\n"
            + "- Use only Manim Community APIs.\n"
            + "- All identifiers must remain ASCII English only.\n"
            + "\n"
            + "When fixing the reported error, do not stop at the first failing line.\n"
            + "- Inspect surrounding code and other structurally similar locations for the same"
            + " pattern.\n"
            + "- If the same root cause can appear in multiple places, proactively fix all of them"
            + " in this response.\n"
            + "- Check repeated MathTex, Tex, Text, animation, helper-method, and scene-transition"
            + " patterns for the same risk.\n"
            + "- Prefer a systematic fix over a one-off local edit.\n"
            + "\n"
            + "You must also enforce these rules:\n"
            + "- Rule 1: Do not store mobjects across scene methods via `self.xxx` for reuse in"
            + " other scenes.\n"
            + "- Rule 2: Preserve scene continuity; do not fix layout issues by clearing the whole"
            + " scene between every storyboard beat unless a full reset is genuinely required.\n"
            + "- Rule 3: Do not hardcode numeric subobject indexing into `MathTex`.\n"
            + "- Rule 4: All class names, method names, and variable names must use ASCII English"
            + " only.\n"
            + "- Keep layout within the safe area: x in [-6.5, 6.5], y in [-3.5, 3.5].\n";

    public static String renderFixUserPrompt(String code, String error) {
        return renderFixUserPrompt(code, error, Collections.emptyList());
    }

    public static String renderFixUserPrompt(String code, String error, List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "The following Manim code failed to render:\n\n"
                + "```python\n"
                + "%s\n"
                + "```\n\n"
                + "Error output:\n"
                + "```\n"
                + "%s\n"
                + "```\n",
                code, error));

        sb.append("\nPlease fix the reported error and also inspect nearby and structurally"
                + " similar code paths for the same root cause. If the same kind of failure could"
                + " happen elsewhere in this file, fix those places too in the returned full code.\n"
                + "\nRemember: Return ONLY the single Python code block containing the full file. No explanation.\n");

        if (fixHistory != null && !fixHistory.isEmpty()) {
            sb.append("\nPrevious fix attempts to avoid repeating:\n");
            for (int i = 0; i < fixHistory.size(); i++) {
                sb.append(String.format("  Attempt %d: %s\n", i + 1,
                        fixHistory.get(i).length() > 100
                                ? fixHistory.get(i).substring(0, 100) + "..."
                                : fixHistory.get(i)));
            }
        }

        return sb.toString();
    }
}
