package com.automanim.util;

import com.automanim.model.Narrative.Storyboard;
import com.automanim.model.Narrative.StoryboardAction;
import com.automanim.model.Narrative.StoryboardObject;
import com.automanim.model.Narrative.StoryboardScene;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public final class PromptTemplates {

    private static final String MANIM_SYNTAX_MANUAL_RESOURCE = "llm/manim_syntax_manual.md";

    private PromptTemplates() {}

    private static final class ManimSyntaxManualHolder {
        private static final String VALUE = loadPromptResource(MANIM_SYNTAX_MANUAL_RESOURCE);
    }

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
                                                    String targetDescription,
                                                    boolean manimSpecific) {
        String workflowLabel = manimSpecific
                ? "multi-stage Manim animation generation workflow"
                : "multi-stage teaching animation generation workflow";
        return "You are working inside a " + workflowLabel + ".\n"
                + "Current workflow stage: " + sanitizePromptText(stageLabel, "Unknown stage") + "\n"
                + "Current substep: " + sanitizePromptText(substepLabel, "Unknown substep") + "\n"
                + "Overall workflow: " + WORKFLOW_OVERVIEW + "\n"
                + "Final animation target: " + sanitizePromptText(targetTitle, "Unknown target") + "\n"
                + "Final target description: "
                + sanitizePromptText(targetDescription, "No explicit target description is available yet.")
                + "\n"
                + "Keep the full target in mind, but perform only the responsibility of the current substep.\n\n";
    }

    private static String appendManimSyntaxManual(String systemPrompt) {
        return systemPrompt
                + "\n\n"
                + "Manim syntax reference manual:\n"
                + "Follow the guidance below whenever you generate or revise Manim code.\n\n"
                + ManimSyntaxManualHolder.VALUE;
    }

    public static String ensureManimSyntaxManual(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return appendManimSyntaxManual("");
        }
        if (prompt.contains(ManimSyntaxManualHolder.VALUE)) {
            return prompt;
        }
        return appendManimSyntaxManual(prompt);
    }

    private static String loadPromptResource(String resourceName) {
        try (InputStream input = PromptTemplates.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Classpath resource not found: " + resourceName);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load classpath resource: " + resourceName, e);
        }
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
                        "Explain and solve the math problem \"%s\" through a coherent teaching"
                                + " animation. The goal is not only to reach the answer, but to"
                                + " help the viewer understand why it works. The animation should"
                                + " culminate in the final conclusion \"%s\": %s",
                        safeTarget, safeRootConcept, safeRootDescription);
            }
            return String.format(
                    "Explain and solve the math problem \"%s\" through a coherent teaching"
                            + " animation that leads to the final conclusion \"%s\" while helping"
                            + " the viewer understand the reasoning.",
                    safeTarget, safeRootConcept);
        }

        if (!safeRootDescription.isEmpty()) {
            return safeRootDescription;
        }
        return String.format(
                "Explain the concept \"%s\" through a coherent teaching animation built from the"
                        + " necessary prerequisites up to the final idea.",
                safeTarget);
    }

    public static String foundationCheckSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Foundation sufficiency check",
                targetTitle,
                targetDescription,
                false
        ) + FOUNDATION_CHECK_SYSTEM;
    }

    public static String prerequisitesSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Direct prerequisite extraction",
                targetTitle,
                targetDescription,
                false
        ) + PREREQUISITES_SYSTEM;
    }

    public static String inputModeClassifierSystemPrompt(String inputText) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Input mode classification",
                inputText,
                "Decide whether this input should follow the concept-explanation workflow or the"
                        + " problem-solving workflow.",
                false
        ) + INPUT_MODE_CLASSIFIER_SYSTEM;
    }

    public static String problemStepGraphSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 0 / Exploration",
                "Problem solution-step graph planning",
                targetTitle,
                targetDescription,
                false
        ) + PROBLEM_STEP_GRAPH_SYSTEM;
    }

    public static String mathEnrichmentSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 1a / Mathematical Enrichment",
                "Mathematical content enrichment",
                targetTitle,
                targetDescription,
                false
        ) + MATH_ENRICHMENT_SYSTEM;
    }

    public static String visualDesignSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 1b / Visual Design",
                "Scene visual design",
                targetTitle,
                targetDescription,
                true
        ) + VISUAL_DESIGN_SYSTEM;
    }

    public static String narrativeSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 1c / Narrative Composition",
                "Storyboard composition",
                targetTitle,
                targetDescription,
                true
        ) + NARRATIVE_SYSTEM;
    }

    public static String codeGenerationSystemPrompt(String targetTitle, String targetDescription) {
        return appendManimSyntaxManual(buildWorkflowSystemPrefix(
                "Stage 2 / Code Generation",
                "Generate executable Manim code",
                targetTitle,
                targetDescription,
                true
        ) + CODE_GENERATION_SYSTEM);
    }

    public static String codeValidationFixSystemPrompt(String targetTitle, String targetDescription) {
        return appendManimSyntaxManual(buildWorkflowSystemPrefix(
                "Stage 2 / Code Fix",
                "Repair generated code after validation findings",
                targetTitle,
                targetDescription,
                true
        ) + CODE_VALIDATION_FIX_SYSTEM);
    }

    public static String codeEvaluationSystemPrompt(String targetTitle, String targetDescription) {
        return buildWorkflowSystemPrefix(
                "Stage 3 / Code Evaluation",
                "Review code for layout, continuity, pacing, and clutter risk",
                targetTitle,
                targetDescription,
                true
        ) + CODE_EVALUATION_SYSTEM;
    }

    public static String codeRevisionSystemPrompt(String targetTitle, String targetDescription) {
        return appendManimSyntaxManual(buildWorkflowSystemPrefix(
                "Stage 3 / Code Evaluation",
                "Revise Manim code after code evaluation before render",
                targetTitle,
                targetDescription,
                true
        ) + CODE_REVISION_SYSTEM);
    }

    public static String renderFixSystemPrompt(String targetTitle, String targetDescription) {
        return appendManimSyntaxManual(buildWorkflowSystemPrefix(
                "Stage 4 / Render Fix",
                "Repair Manim code after render failure",
                targetTitle,
                targetDescription,
                true
        ) + RENDER_FIX_SYSTEM);
    }

    public static String sceneLayoutFixSystemPrompt(String targetTitle, String targetDescription) {
        return appendManimSyntaxManual(buildWorkflowSystemPrefix(
                "Stage 5 / Scene Evaluation Fix",
                "Revise Manim code after geometry-based scene evaluation",
                targetTitle,
                targetDescription,
                true
        ) + CODE_REVISION_SYSTEM);
    }

    // =====================================================================
    // Stage 0: Exploration
    // =====================================================================

    public static final String FOUNDATION_CHECK_SYSTEM =
            "Decide whether the current node is already small, concrete, and self-contained"
            + " enough to serve as one animation-ready teaching beat for middle-school learners.\n"
            + "\n"
            + "Workflow context:\n"
            + "- The DAG will be topologically traversed to produce a sequence of animated scenes.\n"
            + "- Each node becomes one scene; foundation (leaf) nodes become the simplest opening"
            + " scenes, so they must be truly self-explanatory.\n"
            + "- This stage does not write the final storyboard. It decides whether a node can"
            + " stand on its own as one simple teaching beat before later scenes build on it.\n"
            + "- Keep in mind a teaching flow that usually moves from observation to relation to"
            + " transformation to conclusion. Foundation nodes should support the earliest,"
            + " most intuitive part of that flow.\n"
            + "- Do not judge only by mathematical sufficiency. Judge whether this node can"
            + " support one clear animated teaching beat with a concrete focus and no hidden jump.\n"
            + "\n"
            + "Decide whether the current step is already basic enough for an ordinary"
            + " middle-school student to understand directly, while still being clearly relevant"
            + " to the final teaching goal.\n"
            + "\n"
            + "Rules:\n"
            + "1. Judge from a middle-school student's perspective using clear, concrete,"
            + " non-university-level expectations.\n"
            + "2. The step must be clearly useful for the final teaching goal.\n"
            + "3. A good foundation node should be understandable as one short, intuitive scene,"
            + " not as a compressed chain of hidden reasoning.\n"
            + "4. If it still bundles multiple sub-ideas, steps, cases, or layers, answer no.\n"
            + "5. If it requires advanced abstraction, formal derivation, or significant prior"
            + " knowledge, answer no.\n"
            + "6. If it would be hard to introduce visually or naturally without first proving"
            + " something else, answer no.\n"
            + "7. Bias toward yes when uncertain.\n"
            + "\n"
            + "If tools are available, call them.\n"
            + "Otherwise return only a JSON object with the same shape as the tool output.\n"
            + "- Required field: \"is_foundation\" (boolean)\n"
            + "- Optional field: \"reason\" (short string)\n"
            + "- Do not return yes/no prose, markdown, or any extra text outside the JSON object.\n"
            + "\n"
            + "Example output:\n"
            + "{\"is_foundation\": false, \"reason\": \"The step still bundles multiple"
            + " sub-ideas and should be decomposed before it becomes a leaf scene.\"}";

    public static final String PREREQUISITES_SYSTEM =
            "Return the direct prerequisite teaching beats needed before the current node can"
            + " be animated clearly for middle-school learners.\n"
            + "\n"
            + "Workflow context:\n"
            + "- The DAG is topologically traversed to generate one animated scene per node.\n"
            + "- This exploration stage is responsible for the knowledge structure, not for detailed"
            + " visual staging.\n"
            + "- Later stages will turn this graph into a teaching animation script and storyboard,"
            + " so choose prerequisites that support a natural, easy-to-follow learning flow.\n"
            + "- For each prerequisite, write a short `step` describing what should be taught or"
            + " shown in that beat.\n"
            + "- Also write a short `reason` explaining why that beat is needed before the current"
            + " node and what understanding it unlocks.\n"
            + "- The step and reason must stay conceptual: do not describe colors, camera moves,"
            + " animation timing, or layout coordinates.\n"
            + "- Prefer a progression that can naturally move from observation to key relation to"
            + " transformation or representation and then to the target idea.\n"
            + "- Do not merely mirror a textbook dependency list. Choose beats that help a later"
            + " animation reveal the reasoning gradually instead of jumping to the answer.\n"
            + "\n"
            + "Rules:\n"
            + "1. Return only truly necessary prerequisites, not merely helpful background.\n"
            + "2. Keep them directly relevant to the final teaching goal.\n"
            + "3. Prefer beats that help students first see or notice something concrete before"
            + " asking them to accept a formal statement.\n"
            + "4. Avoid overly broad, tangential, or generic topics.\n"
            + "5. Each prerequisite should express one clear, atomic beat that could support"
            + " one focused scene.\n"
            + "6. Avoid synonyms, near-duplicates, parent-child duplication, and hidden multi-step"
            + " bundles.\n"
            + "7. Prefer simpler, more visualizable beats tightly connected to the goal.\n"
            + "8. Replace overly advanced candidates with more basic but still mathematically"
            + " relevant ones.\n"
            + "9. In the reason, explain the teaching job of the prerequisite: what it helps"
            + " the student notice, understand, or use in a later beat.\n"
            + "10. Return at most 3 to 5 items, ordered by necessity.\n"
            + "\n"
            + "If tools are available, call them.\n"
            + "Otherwise return a JSON array of objects, each with two fields:\n"
            + "- \"step\": a short description of the prerequisite beat\n"
            + "- \"reason\": one or two sentences explaining why this beat is needed and what"
            + " understanding it should establish\n"
            + "\n"
            + "Example:\n"
            + "[{\"step\": \"Introduce the unit circle as the reference object\","
            + " \"reason\": \"It gives later coordinate relationships a concrete geometric"
            + " anchor instead of an abstract formula.\"}]";

    public static final String INPUT_MODE_CLASSIFIER_SYSTEM =
            "You are a routing classifier for a math teaching-animation workflow.\n"
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
            "Plan animation-ready teaching beats for a middle-school math problem.\n"
            + "\n"
            + "Your job is to produce animation-ready teaching beats for solving the problem,"
            + " not a textbook-style prerequisite DAG and not an answer-only solution.\n"
            + "Build a small dependency graph whose nodes are the major beats that a good"
            + " animation should present in order.\n"
            + "Each node should feel close to one scene or one major reveal that a storyboard"
            + " writer could directly develop.\n"
            + "For each node, write a short `step` describing what this beat does or shows.\n"
            + "Also write a short `reason` explaining why this beat is needed, what the viewer"
            + " should notice or understand, and why it must come before the next beat.\n"
            + "You may mention the high-level visual reasoning move, but do not specify exact"
            + " camera motion, colors, layout coordinates, or styling.\n"
            + "\n"
            + "Design principles:\n"
            + "1. Start by establishing the problem situation, givens, and goal clearly.\n"
            + "2. Prefer a route that is easy to animate and easy to understand before one"
            + " that is only formally elegant.\n"
            + "3. Make the key observation, transformation, construction, symmetry, or"
            + " insight explicit whenever it is the real turning point of the solution.\n"
            + "4. Avoid hidden leaps. If the viewer must notice something before accepting a"
            + " formula or conclusion, create a separate beat for that realization.\n"
            + "5. One node should represent one teachable beat or major reveal, not a tiny"
            + " algebraic micro-step.\n"
            + "6. Merge steps that would naturally belong to the same animated scene; split"
            + " only when understanding would otherwise jump too far.\n"
            + "7. If a rigorous step is hard to animate directly, replace it or precede it"
            + " with a more visual intermediate beat that preserves the mathematical meaning.\n"
            + "8. Prefer a compact graph of about 4 to 7 beats for a typical middle-school"
            + " problem unless the problem truly needs more.\n"
            + "\n"
            + "Rules:\n"
            + "1. Focus only on beats that are genuinely needed to animate and explain the"
            + " solution.\n"
            + "2. Each node should represent one animation-ready teaching beat, not a broad"
            + " topic and not a fragmented micro-step.\n"
            + "3. Use node_type from: problem, observation, construction, derivation, conclusion.\n"
            + "4. The graph should usually include a `problem` node that introduces the givens"
            + " and target before later beats build on it.\n"
            + "5. The root_id must be the final conclusion node, and that node must have min_depth 0.\n"
            + "6. Earlier required beats should have larger min_depth values.\n"
            + "7. Dependencies must point only to direct prerequisite beats.\n"
            + "8. Avoid generic labels such as \"analyze problem\", \"do calculation\", or"
            + " \"get answer\" unless they are made specific and teachable.\n"
            + "9. Use the same language as the user input for `step` when natural. Keep"
            + " `id` short and stable.\n"
            + "10. In each reason, emphasize the teaching job and viewer takeaway, not"
            + " only the local mathematical result.\n"
            + "11. Prefer a graph that a storyboard stage could almost directly convert into"
            + " scenes with only light merging.\n"
            + "\n"
            + "Return a JSON object with this shape:\n"
            + "{\n"
            + "  \"root_id\": \"final_node_id\",\n"
            + "  \"nodes\": [\n"
            + "    {\n"
            + "      \"id\": \"final_node_id\",\n"
            + "      \"step\": \"Present the final answer\",\n"
            + "      \"reason\": \"This beat resolves the problem and summarizes why the method works\",\n"
            + "      \"node_type\": \"conclusion\",\n"
            + "      \"min_depth\": 0,\n"
            + "      \"is_foundation\": false\n"
            + "    },\n"
            + "    {\n"
            + "      \"id\": \"previous_step\",\n"
            + "      \"step\": \"Make the key observation or construction\",\n"
            + "      \"reason\": \"This beat creates the insight needed before the conclusion can feel justified\",\n"
            + "      \"node_type\": \"derivation\",\n"
            + "      \"min_depth\": 1,\n"
            + "      \"is_foundation\": false\n"
            + "    }\n"
            + "  ],\n"
            + "  \"prerequisite_edges\": {\n"
            + "    \"final_node_id\": [\"previous_step\"]\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "The edge direction: node -> direct dependencies needed before it.\n"
            + "If tools are available, call them.\n"
            + "Return JSON only.";

    // =====================================================================
    // Stage 1a: Mathematical Enrichment
    // =====================================================================

    public static final String MATH_ENRICHMENT_SYSTEM =
            "You are a mathematics educator preparing content for a teaching animation.\n"
            + "\n"
            + "The system context includes the target problem or concept together with the ordered"
            + " solution-step chain when available.\n"
            + "The user message describes the current step being enriched.\n"
            + "Keep the current step consistent with the target problem and the overall solution"
            + " path. Do not invent a different route, extra givens, or unsupported claims.\n"
            + "Use the current step's Stage 0 reason as teaching context, but return only the"
            + " mathematical content that genuinely improves teaching quality.\n"
            + "Do not treat this as a request for a full textbook-style solution. Return the"
            + " mathematical material that will help later animation stages show what students"
            + " should notice, understand, and connect.\n"
            + "Prefer intuitive interpretations and compact symbolic support over long formal"
            + " derivations when both serve the same teaching goal.\n"
            + "\n"
            + "LaTeX rules:\n"
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
            + "If the current step does not need formulas, return empty fields such as"
            + " \"equations\": [] and \"definitions\": {} instead of inventing notation.\n"
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
            + "The system context includes the target problem or concept together with the ordered"
            + " solution-step chain when available.\n"
            + "The user message describes the current step being designed, nearby step context,"
            + " prerequisite visual context, and the current color palette state.\n"
            + "For problem-solving tasks, the original problem statement and the ordered"
            + " solution-step chain are source of truth and must stay consistent across designs.\n"
            + "Your job is to turn abstract reasoning into something the viewer can see happen, "
            + "compare, track, and understand.\n"
            + "Treat the step chain as the logical backbone, but choose visuals that make the"
            + " key relation or change perceptible on screen.\n"
            + "\n"
            + "The exploration-stage `reason` field explains why the step matters in the teaching"
            + " flow; it is not a visual specification.\n"
            + "\n"
            + "Visual design principles:\n"
            + "- Prefer direct visual reasoning over text-heavy explanation whenever possible.\n"
            + "- Show change through motion, comparison, decomposition, transformation,"
            + " symmetry, highlighting, or auxiliary constructions when those reveal the idea.\n"
            + "- Keep the viewer oriented around one stable diagram whenever possible, and change"
            + " only the layer that carries the new insight.\n"
            + "- Let formulas support the visual argument instead of replacing it.\n"
            + "- If a reasoning step is not naturally visible, design a mathematically faithful"
            + " proxy such as a comparison view, a staged construction, a before/after"
            + " transformation, or a highlighted substructure.\n"
            + "- For each scene, implicitly answer: what is on screen now, what changes, and why"
            + " that change helps the learner understand the math.\n"
            + "\n"
            + "Do not invent new givens, helper claims, or alternative solution branches that"
            + " are not supported by the provided problem context and step graph.\n"
            + "\n"
            + "Screen-space constraints for a 16:9 frame (roughly 14x8 units):\n"
            + "- Keep important content within x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Leave at least 1 unit of margin from the frame edge.\n"
            + "- A scene should usually contain no more than 6 to 8 main visual elements.\n"
            + "- The layout field must describe concrete projected placement.\n"
            + "- If the idea truly needs spatial depth, set `scene_mode` to `3d`, describe the"
            + " projected layout, add a concise `camera_plan`, and use"
            + " `screen_overlay_plan` for text that should stay fixed in frame.\n"
            + "\n"
            + "Return a JSON object containing:\n"
            + "- \"visual_description\": describe what visual elements appear in the scene, such"
            + " as geometry, labels, highlights, axes, arrows, or formula blocks. Focus on what"
            + " is shown and what mathematical relation is being made visible, not on color"
            + " assignment, exact placement, or animation order.\n"
            + "- \"scene_mode\" when useful: return `2d` or `3d`.\n"
            + "- \"camera_plan\" when useful: give a concise camera orientation or motion plan.\n"
            + "- \"screen_overlay_plan\" when useful: state what text or formulas remain fixed in"
            + " frame.\n"
            + "- \"color_scheme\": describe the visual styling of those elements, especially which"
            + " colors or emphasis styles belong to the main objects, highlights, labels, and"
            + " supporting annotations.\n"
            + "- \"layout\": describe the projected arrangement on the 16:9 frame, including where"
            + " each important element appears on screen and how the composition avoids overflow.\n"
            + "- \"animation_description\" when useful: describe the animation order, motion,"
            + " emphasis changes, and how the scene evolves over time so the reasoning becomes"
            + " visually legible.\n"
            + "- \"transitions\" when useful: describe the scene transition style when it matters.\n"
            + "- \"duration\" when useful: approximate duration in seconds.\n"
            + "- \"color_palette\" as an optional array of Manim color names.\n"
            + "\n"
            + "Do not add optional fields unless they are genuinely useful.\n"
            + "\n"
            + "Example output:\n"
            + "{\n"
            + "  \"visual_description\": \"Show one triangle, vertex labels, a highlighted"
            + " hypotenuse, and one short formula block.\",\n"
            + "  \"color_scheme\": \"Use BLUE for the triangle outline, YELLOW for the highlighted"
            + " hypotenuse, and WHITE for labels and formula text.\",\n"
            + "  \"layout\": \"Keep the triangle centered, place labels close to the vertices,"
            + " and place the formula block in the upper-right safe area.\",\n"
            + "  \"animation_description\": \"Draw the triangle first, then fade in the labels,"
            + " pulse the hypotenuse, and finally write the formula.\",\n"
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
            + "      \"scene_mode\": \"2d|3d\",\n"
            + "      \"camera_anchor\": \"main visual anchor or focus region\",\n"
            + "      \"camera_plan\": \"camera orientation or motion for this scene\",\n"
            + "      \"layout_goal\": \"explicit spatial arrangement\",\n"
            + "      \"safe_area_plan\": \"how the projected screen layout stays inside x in [-6.5, 6.5] and y in [-3.5, 3.5]\",\n"
            + "      \"screen_overlay_plan\": \"text or formulas that stay fixed in frame when needed\",\n"
            + "      \"step_refs\": [\"relevant step or node names\"],\n"
            + "      \"entering_objects\": [\n"
            + "        {\n"
            + "          \"id\": \"formula_main\",\n"
            + "          \"kind\": \"formula|geometry|text|axis|label|highlight\",\n"
            + "          \"content\": \"what appears on screen\",\n"
            + "          \"placement\": \"exact initial position\",\n"
            + "          \"style\": \"color/scale/emphasis\",\n"
            + "          \"source_node\": \"optional source step\"\n"
            + "        }\n"
            + "      ],\n"
            + "      \"persistent_objects\": [\"object ids that remain into the next scene\"],\n"
            + "      \"exiting_objects\": [\"object ids that must fade out or be removed\"],\n"
            + "      \"actions\": [\n"
            + "        {\n"
            + "          \"order\": 1,\n"
            + "          \"type\": \"create|transform|move|highlight|fade_out|camera_focus|camera_move|camera_rotate\",\n"
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
            + "      \"scene_mode\": \"2d\",\n"
            + "      \"camera_anchor\": \"center\",\n"
            + "      \"camera_plan\": \"Static 2D camera.\",\n"
            + "      \"layout_goal\": \"Keep the diagram centered and reserve the upper-right"
            + " corner for one short formula.\",\n"
            + "      \"safe_area_plan\": \"Keep the diagram inside the central safe area and leave"
            + " a one-unit margin from all edges.\",\n"
            + "      \"screen_overlay_plan\": \"No fixed screen overlay needed.\",\n"
            + "      \"step_refs\": [\"original problem statement\"],\n"
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
            + "The user will provide the workflow target together with an ordered step progression"
            + " chain enriched with mathematical and visual context.\n"
            + "\n"
            + "Write a scene-by-scene storyboard that:\n"
            + "- begins with a clear hook or motivation\n"
            + "- explains foundations before advanced content\n"
            + "- treats the output as an animation script, not as a plain solved answer\n"
            + "- makes clear what appears on screen, what changes on screen, and what the student"
            + " is meant to notice in each beat\n"
            + "- states the teaching purpose of each scene through goal, narration, object flow,"
            + " and ordered actions\n"
            + "- preserves provided LaTeX formulas exactly when referenced\n"
            + "- turns every visual change into explicit staging data rather than vague prose\n"
            + "- feels like one connected visual argument, not a stack of unrelated mini-lessons\n"
            + "- keeps a stable diagram/layout whenever possible and changes only the necessary layer\n"
            + "- prefers intuitive visual understanding before the strict final justification when"
            + " that improves teaching clarity\n"
            + "\n"
            + "Screen-space constraints for storyboard design:\n"
            + "- Treat the frame as 16:9 with important projected content kept inside"
            + " x in [-6.5, 6.5] and y in [-3.5, 3.5].\n"
            + "- Leave about 1 unit of margin from the frame edge.\n"
            + "- Keep the number of simultaneous main visual elements to about 6 to 8.\n"
            + "- Put large formulas near an edge or corner rather than over the main geometry.\n"
            + "- If a layout would overflow, split it into multiple scenes or reduce what is shown.\n"
            + "- Every scene must include `safe_area_plan` explaining how the layout avoids overflow.\n"
            + "- If a scene genuinely needs depth, mark `scene_mode` as `3d`, keep `layout_goal`"
            + " about projected screen placement, add a concise `camera_plan`, and use"
            + " `screen_overlay_plan` for any text that must stay fixed in frame.\n"
            + "\n"
            + "Important selection rule:\n"
            + "- Mathematical enrichment fields such as equations and definitions are optional"
            + " supporting material.\n"
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
            + "- `camera_plan` should be explicit whenever the scene is 3D.\n"
            + "- Use safe_area_plan to state the anti-overflow strategy for each scene.\n"
            + "- Prefer 3 to 5 strong scenes for problem-solving workflows unless the lesson truly"
            + " needs more.\n"
            + "- Keep narration concise; the storyboard JSON is primarily for staging clarity.\n"
            + "\n"
            + "Problem-solving focus rules:\n"
            + "- If the target is a math problem, every scene must directly advance the solution.\n"
            + "- Do not collapse the whole solution into one answer scene or one dense narration"
            + " block.\n"
            + "- Start by establishing the problem situation, givens, and what must be found or"
            + " proved before the main derivation begins.\n"
            + "- Do not give secondary facts, historical remarks, or theorem side-quests their own"
            + " standalone scenes unless they are indispensable.\n"
            + "- Merge nearby steps when they serve one reasoning move.\n"
            + "- Keep one stable diagram and evolve it with small changes from scene to scene.\n"
            + "- Auxiliary facts such as equal-angle laws should appear as brief support, not as"
            + " the main headline, unless the whole problem is about that law.\n"
            + "- If one formal step is not naturally visual, represent it through a more visible"
            + " intermediate beat that preserves the mathematical meaning.\n"
            + "\n"
            + STORYBOARD_SCHEMA_GUIDE
            + "\n"
            + "Return only valid JSON. Do not wrap it in markdown. If tools are available, call them.";

    public static String narrativeUserPrompt(String targetConcept, String stepContext) {
        return String.format(
                "Target concept: %s\n\nStep progression chain:\n%s\n\n"
                        + "Produce a continuity-safe storyboard JSON, not free text.",
                targetConcept, stepContext);
    }

    public static String problemNarrativeUserPrompt(String problemStatement,
                                                    String solvingContext,
                                                    int targetSceneCount) {
        return String.format(
                "Math problem to solve: %s\n\nOrdered solution-step graph context:\n%s\n\n"
                        + "Write the animation as a structured problem-solving storyboard, not as"
                        + " a plain written solution.\n"
                        + "Start by establishing the problem situation clearly, then move through"
                        + " the key observation/construction/derivation steps in solving order,"
                        + " and end with the final answer and why it is correct or optimal.\n"
                        + "For each scene, make the on-screen progression legible: what appears,"
                        + " what changes, and what the student should realize from that change.\n"
                        + "Prefer intuitive visual reasoning before compact formal wording when"
                        + " both are mathematically sound.\n"
                        + "If some justification is awkward to animate directly, replace it with a"
                        + " more visual intermediate beat rather than skipping the reasoning.\n"
                        + "Target about %d scenes total.\n"
                        + "Do not force one scene per node; merge nodes whenever that improves"
                        + " focus and continuity.\n"
                        + "Keep the viewer oriented around one persistent diagram, with only the"
                        + " essential new element introduced in each scene.\n"
                        + "Return storyboard JSON only.",
                problemStatement, solvingContext, targetSceneCount);
    }

    public static String storyboardCodegenPrompt(String targetConcept, Storyboard storyboard) {
        return storyboardCodegenPrompt(targetConcept, buildCompactStoryboardJsonForCodegen(storyboard));
    }

    public static String storyboardCodegenPrompt(String targetConcept, String storyboardJson) {
        return String.format(
                "Target concept: %s\n\n"
                        + "Use the following compact storyboard JSON as the source of truth for staging,\n"
                        + "object identity, continuity, and scene execution.\n"
                        + "The payload is intentionally reduced for code generation, so focus on the\n"
                        + " scene list, object flow, actions, and safety constraints.\n"
                        + "- Treat every object id as a stable visual identity.\n"
                        + "- If an id persists into the next scene, keep or transform the same"
                        + " mobject instead of redrawing it from scratch.\n"
                        + "- Remove only the objects listed in exiting_objects unless a full reset is"
                        + " explicitly required by the storyboard.\n"
                        + "- If a scene uses `scene_mode` = `3d`, use `ThreeDScene`, follow"
                        + " `camera_plan`, and judge layout in projected screen space.\n"
                        + "- Use `screen_overlay_plan` with `add_fixed_in_frame_mobjects` for"
                        + " text or formulas that must stay readable during camera motion.\n"
                        + "- Respect camera_anchor, entering_objects placements, and notes_for_codegen"
                        + " when placing formulas, labels, and diagrams.\n"
                        + "- Respect safe_area_plan so content stays inside the storyboard's intended"
                        + " safe frame.\n"
                        + "- Preserve the storyboard's scene order and teaching intent.\n\n"
                        + "Compact storyboard JSON:\n```json\n%s\n```\n\n"
                        + "Remember: Return ONLY the single Python code block. No explanation.",
                targetConcept, storyboardJson);
    }

    public static String buildCompactStoryboardJsonForCodegen(Storyboard storyboard) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        if (storyboard == null) {
            root.putArray("scenes");
            return JsonUtils.toPrettyJson(root);
        }

        putNonBlank(root, "continuity_plan", storyboard.getContinuityPlan());
        putTrimmedStringArray(root, "global_visual_rules", storyboard.getGlobalVisualRules());

        ArrayNode scenesArray = root.putArray("scenes");
        if (storyboard.getScenes() != null) {
            for (StoryboardScene scene : storyboard.getScenes()) {
                if (scene == null) {
                    continue;
                }

                ObjectNode sceneNode = scenesArray.addObject();
                putNonBlank(sceneNode, "scene_id", scene.getSceneId());
                putNonBlank(sceneNode, "title", scene.getTitle());
                putNonBlank(sceneNode, "narration", scene.getNarration());
                if (scene.getDurationSeconds() > 0) {
                    sceneNode.put("duration_seconds", scene.getDurationSeconds());
                }
                putNonBlank(sceneNode, "scene_mode", scene.getSceneMode());
                putNonBlank(sceneNode, "camera_anchor", scene.getCameraAnchor());
                putNonBlank(sceneNode, "camera_plan", scene.getCameraPlan());
                putNonBlank(sceneNode, "safe_area_plan", scene.getSafeAreaPlan());
                putNonBlank(sceneNode, "screen_overlay_plan", scene.getScreenOverlayPlan());
                putTrimmedStringArray(sceneNode, "step_refs", scene.getStepRefs());

                ArrayNode enteringObjects = sceneNode.putArray("entering_objects");
                if (scene.getEnteringObjects() != null) {
                    for (StoryboardObject object : scene.getEnteringObjects()) {
                        if (object == null) {
                            continue;
                        }
                        ObjectNode objectNode = enteringObjects.addObject();
                        putNonBlank(objectNode, "id", object.getId());
                        putNonBlank(objectNode, "kind", object.getKind());
                        putNonBlank(objectNode, "content", object.getContent());
                        putNonBlank(objectNode, "placement", object.getPlacement());
                        putNonBlank(objectNode, "style", object.getStyle());
                        putNonBlank(objectNode, "source_node", object.getSourceNode());
                    }
                }

                putTrimmedStringArray(sceneNode, "persistent_objects", scene.getPersistentObjects());
                putTrimmedStringArray(sceneNode, "exiting_objects", scene.getExitingObjects());

                ArrayNode actions = sceneNode.putArray("actions");
                if (scene.getActions() != null) {
                    for (StoryboardAction action : scene.getActions()) {
                        if (action == null) {
                            continue;
                        }
                        ObjectNode actionNode = actions.addObject();
                        if (action.getOrder() > 0) {
                            actionNode.put("order", action.getOrder());
                        }
                        putNonBlank(actionNode, "type", action.getType());
                        putTrimmedStringArray(actionNode, "targets", action.getTargets());
                        putNonBlank(actionNode, "description", action.getDescription());
                    }
                }

                putTrimmedStringArray(sceneNode, "notes_for_codegen", scene.getNotesForCodegen());
            }
        }

        return JsonUtils.toPrettyJson(root);
    }

    private static void putNonBlank(ObjectNode node, String fieldName, String value) {
        String normalized = sanitizePromptText(value, "");
        if (!normalized.isEmpty()) {
            node.put(fieldName, normalized);
        }
    }

    private static void putTrimmedStringArray(ObjectNode node, String fieldName, List<String> values) {
        ArrayNode array = node.putArray(fieldName);
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = sanitizePromptText(value, "");
            if (!normalized.isEmpty()) {
                array.add(normalized);
            }
        }
    }

    public static String codeValidationFixUserPrompt(String sceneName,
                                                     String manimCode,
                                                     List<String> violations) {
        String problemList = (violations == null || violations.isEmpty())
                ? "- Validation failed for an unspecified reason."
                : "- " + String.join("\n- ", violations);
        return String.format(
                "The generated Manim code failed validation checks.\n\n"
                        + "Required scene class name: %s\n\n"
                        + "Current code:\n```python\n%s\n```\n\n"
                        + "Problems found:\n%s\n\n"
                        + "Rewrite the FULL code so it satisfies all validation rules while preserving"
                        + " the original teaching goal. Keep `%s` as the exact scene class name and"
                        + " use ASCII-only Python identifiers.\n"
                        + "While rewriting, also proactively check for common Python and Manim mistakes"
                        + " that would still break execution, such as undefined names, invalid API calls,"
                        + " missing imports, broken helper references, and mismatched scene class usage.\n"
                        + "Return complete syntactically valid, runnable Python that can render successfully.\n"
                        + "Return ONLY the full Python code block.",
                sceneName, manimCode, problemList, sceneName);
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
                        + "Compact storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Manim code to review:\n```python\n%s\n```\n\n"
                        + "Focus on layout safety, continuity between scenes, pacing versus narration,"
                        + " clutter risk, likely offscreen placement, and whether semantically"
                        + " constrained visual elements are placed in the correct spatial"
                        + " relationship to the objects they describe.\n"
                        + "For 3D scenes, focus on projected screen layout, camera readability, and"
                        + " fixed-in-frame overlays for explanatory text.\n"
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
                        + "Compact storyboard JSON (source of truth):\n```json\n%s\n```\n\n"
                        + "Static visual analysis:\n```json\n%s\n```\n\n"
                        + "Structured code review:\n```json\n%s\n```\n\n"
                + "Current Manim code:\n```python\n%s\n```\n\n"
                + "Rewrite the FULL code to improve layout discipline, reduce clutter,"
                + " preserve continuity with transforms instead of resets, keep recurring"
                + " anchors stable, correct semantically wrong placements such as angle arcs,"
                + " labels, braces, markers, or highlights attached to the wrong geometry,"
                + " better match scene pacing to narration, and in 3D scenes keep the camera"
                + " readable while fixing overlays that should stay fixed in frame.\n"
                + "While making those revisions, also check for common Python and Manim runtime"
                + " mistakes such as undefined names, stale object references, invalid API usage,"
                + " missing imports, and scene-type mismatches, and fix them if found.\n"
                + "Return complete syntactically valid, runnable Python that can render successfully.\n"
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
            + "3D scene rules:\n"
            + "- If the storyboard marks a scene as `3d` or the content truly needs spatial depth,"
            + " use `ThreeDScene`.\n"
            + "- Apply the requested camera orientation or motion explicitly.\n"
            + "- In 3D scenes, keep explanatory text and formulas fixed with"
            + " `add_fixed_in_frame_mobjects` unless the storyboard says otherwise.\n"
            + "- Judge layout in projected screen space, not raw world coordinates.\n"
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

    public static final String CODE_VALIDATION_FIX_SYSTEM =
            "You are a Manim code correction specialist.\n"
            + "\n"
            + "You will receive generated Manim code together with validation failures.\n"
            + "Rewrite the full file so it becomes valid, consistent, and ready for the next"
            + " workflow stage.\n"
            + "\n"
            + "Priorities:\n"
            + "- Fix every reported validation problem, not only the first one.\n"
            + "- Preserve the intended teaching content and visual story.\n"
            + "- Keep the required scene class name exactly as requested.\n"
            + "- Use ASCII-only class names, method names, variables, and helper identifiers.\n"
            + "- Return complete, syntactically valid, runnable Python source only, never a patch or explanation.\n"
            + "- Proactively check for common execution mistakes such as undefined names,"
            + " invalid Manim API calls, missing imports, broken helper references, and"
            + " scene-class mismatches, and fix them in the same pass.\n"
            + "\n"
            + "Also enforce these workflow rules:\n"
            + "- Do not store mobjects on `self` just to reuse them across scene methods.\n"
            + "- Do not hardcode numeric subobject indexing into `MathTex`.\n"
            + "- Preserve visual continuity instead of clearing the whole scene between beats"
            + " unless a full reset is genuinely required.\n"
            + "- Keep layout inside the safe area whenever possible.\n"
            + "- In 3D scenes, preserve readable camera setup and keep overlay text fixed in"
            + " frame when appropriate.\n";

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
            + "- For 3D scenes, judge the projected screen image, camera readability, whether"
            + " overlay text/formulas are fixed in frame when needed, and whether `ThreeDScene`"
            + " is used when the scene contains 3D objects or surfaces.\n"
            + "- Treat repeated `to_edge` / large `shift` positioning, heavy text stacking,"
            + " many same-time formulas, and overuse of `FadeIn` / `FadeOut` in place of"
            + " transforms as warning signs.\n"
            + "- Check whether each element is positioned correctly relative to the objects"
            + " it refers to. Penalize semantically wrong placements such as an angle arc"
            + " drawn on the wrong side or outside the intended vertex, labels attached to"
            + " the wrong point or segment, braces spanning the wrong expression, or"
            + " highlights/arrows pointing at the wrong target.\n"
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
            + "- Layout score should also drop when element placement is geometrically or semantically"
            + " wrong even if the frame is not crowded.\n"
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
            + "- Correct geometrically or semantically wrong placements so each visual marker"
            + " stays attached to the object, side, point, angle, or expression it is meant"
            + " to describe.\n"
            + "- In 3D scenes, preserve a readable camera plan and keep overlay text fixed in"
            + " frame when it should not rotate with the world.\n"
            + "- Avoid pushing many objects to the frame edge with repeated `to_edge` or large `shift`.\n"
            + "- Use consistent spacing helpers such as `VGroup(...).arrange(...)`, `next_to`,"
            + " aligned edges, and safe-area scaling.\n"
            + "- Match animation beats to the storyboard durations and narration density.\n"
            + "- Keep the main geometry or motion in the center safe area.\n"
            + "- Also check for common execution mistakes such as undefined names, invalid"
            + " Manim API usage, missing imports, stale helper references, and scene-type"
            + " mismatches, and fix them if they appear.\n"
            + "\n"
            + "Requirements:\n"
            + "- Return ONE SINGLE ```python ... ``` block containing the FULL corrected code.\n"
            + "- Preserve the storyboard intent and scene class name.\n"
            + "- Preserve ASCII-only identifiers.\n"
            + "- Ensure the returned code is complete, syntactically valid, and runnable.\n"
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
            + "- Ensure the returned code is complete, syntactically valid, and runnable.\n"
            + "\n"
            + "When fixing the reported error, do not stop at the first failing line.\n"
            + "- Inspect surrounding code and other structurally similar locations for the same"
            + " pattern.\n"
            + "- If the same root cause can appear in multiple places, proactively fix all of them"
            + " in this response.\n"
            + "- Check repeated MathTex, Tex, Text, animation, helper-method, and scene-transition"
            + " patterns for the same risk.\n"
            + "- Prefer a systematic fix over a one-off local edit.\n"
            + "- Also check for other common runtime mistakes such as undefined names, missing"
            + " imports, invalid API calls, stale helper references, wrong scene base classes,"
            + " and incompatible camera usage, and fix them if present.\n"
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
                + "Also proactively check for common Python and Manim runtime mistakes such as"
                + " undefined names, missing imports, invalid API calls, stale object references,"
                + " and wrong scene or camera usage.\n"
                + "Return complete syntactically valid, runnable Python.\n"
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

    public static String sceneLayoutFixUserPrompt(String code,
                                                  String issueSummary,
                                                  String sceneEvaluationJson,
                                                  List<String> fixHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "The following Manim code rendered, but post-render scene evaluation found layout"
                + " issues in sampled frames.\n\n"
                + "```python\n"
                + "%s\n"
                + "```\n\n"
                + "Issue summary:\n"
                + "```\n"
                + "%s\n"
                + "```\n\n"
                + "Scene evaluation report excerpt:\n"
                + "```json\n"
                + "%s\n"
                + "```\n",
                code,
                issueSummary,
                sceneEvaluationJson));

        sb.append("\nPlease fix the code so the reported sampled frames no longer have elements"
                + " overlapping or going outside the frame. Preserve the intended teaching flow"
                + " and animation meaning. Prefer adjusting positioning, scaling, layout grouping,"
                + " and spacing instead of deleting explanatory content.\n"
                + "Also proactively check for common Python and Manim runtime mistakes such as"
                + " undefined names, missing imports, invalid API calls, stale helper references,"
                + " and scene-type mismatches, and fix them if present.\n"
                + "Return complete syntactically valid, runnable Python.\n"
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
