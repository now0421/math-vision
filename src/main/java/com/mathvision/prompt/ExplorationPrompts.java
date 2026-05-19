package com.mathvision.prompt;

/**
 * Prompts for Stage 0: concept/problem exploration.
 */
public final class ExplorationPrompts {

    private static final String INPUT_MODE_CLASSIFIER_SYSTEM =
            "You are a routing classifier for a math teaching-visualization workflow.\n"
                    + "Choose `problem` for a concrete question, proof, optimization, or exercise to solve.\n"
                    + "Choose `concept` for a topic, theorem, formula, or idea to explain.\n"
                    + "Prefer the most operational interpretation of the user's request.\n\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"input_mode\": \"string, either concept or problem\",\n"
                    + "  \"reason\": \"string, brief routing rationale\"\n"
                    + "}\n\n"
                    + "`input_mode` must be either `concept` or `problem`.\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private static final String CONCEPT_GRAPH_SYSTEM =
            "Plan a compact teaching DAG for a middle-school math concept.\n"
                    + "Prefer concrete visual situations, motion, transformation, construction, or contrast to build intuition. Use motion to reveal the concept's core meaning, key relationship, invariant, trend, or misconception; do not mechanically animate every definition or reasoning detail.\n"
                    + "Start by building a concrete visual situation where the concept naturally arises. When useful, follow with a separate motion or manipulation beat that reveals the relationship, pattern, or invariant the concept captures. Preserve this separation between setup and motion-revealed meaning when it improves clarity.\n"
                    + "Motion is preferred, but not mandatory for every node. Labels, formulas, brief narration, and static annotations may name, summarize, or formalize what is already visible. Do not add redundant nodes only to give every beat an animation.\n"
                    + "Avoid pure text dumps with no visual anchor, separate animated proof nodes for obvious definitions or simple equalities, near-duplicate observations, and decorative motion that does not improve understanding.\n"
                    + "Use node types from: concept, observation, construction, derivation, conclusion.\n"
                    + "The start must be the first entry beat at depth 0, and later beats should progress toward the final conclusion.\n"
                    + "Keep the graph compact, acyclic, and easy to teach in topological order.\n"
                    + "Node rules:\n"
                    + "1. Each node should be one clear teaching beat with one main takeaway.\n"
                    + "2. Prefer describing what the learner sees change or what operation is performed in `step`; if a beat is better as a static naming, summary, or formalization, state it clearly without forcing motion.\n"
                    + "3. Setting up the concept situation, revealing the concept's meaning through motion, and naming or formalizing the concept may be separate beats; keep the setup and core motion reveal distinct when that helps clarity.\n"
                    + "4. Key conceptual insights should usually be shown through visual action, contrast, construction, or a visible trend.\n"
                    + "5. Obvious definitions, equal-length/equal-angle facts, notation explanations, or facts guaranteed directly by a construction should be folded into the relevant node's wording instead of becoming their own nodes.\n"
                    + "6. Do not bundle genuinely different reasoning moves into one node, but also do not over-split a natural continuous fact into multiple nodes.\n"
                    + "7. Every node must stay directly relevant to the final teaching goal.\n"
                    + "8. Add a final comparison beat only when it clearly improves understanding; do not add one for formal completeness.\n\n"
                    + "Edge rules:\n"
                    + "1. Add an edge only for truly necessary prerequisites, not helpful background.\n"
                    + "2. Avoid synonyms, near-duplicates, and parent-child duplication across nodes.\n"
                    + "3. Include prerequisite observations or misconceptions only when they are needed to make the later insight feel earned - preferably through a visual contrast or brief operation.\n\n"
                    + SystemPrompts.ASCII_TEXT_RULES
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"start_id\": \"string, id of the first teaching beat\",\n"
                    + "  \"nodes\": [\n"
                    + "    {\"id\": \"string, unique node id\", \"step\": \"string, one presentation-ready teaching beat\", \"reason\": \"string, why this beat matters in the explanation flow\", \"node_type\": \"string, one of concept|observation|construction|derivation|conclusion\", \"min_depth\": \"integer, minimum distance from the start beat\"}\n"
                    + "  ],\n"
                    + "  \"next_edges\": {\"node_id\": [\"direct_next_node_id\"]},\n"
                    + "  \"teaching_order\": [\"node_id_1\", \"node_id_2\", \"...\"]\n"
                    + "}\n\n"
                    + "`teaching_order` is the intended presentation sequence. It must list every node id exactly once, respecting prerequisite dependencies (a node appears after all its prerequisites).\n"
                    + "The edge direction: node -> direct next beats that should follow it.\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private static final String PROBLEM_GRAPH_SYSTEM =
            "Plan a compact teaching DAG for a middle-school math problem.\n"
                    + "Prefer concrete visual situations, motion, construction, transformation, or contrast to help the learner understand the problem and discover the solution path. Motion should serve two priorities: first, make the objects, conditions, and target visible; second, reveal the truly key solving insight. Do not animate every equality, obvious relationship, or proof detail as its own node.\n"
                    + "Preserve this useful problem-solving structure when applicable: first build the problem situation so the learner sees the objects, conditions, and variable quantities; then use motion or manipulation to reveal what quantity, relation, or target the problem is asking about; then show the key construction or transformation; finally present the visual conclusion, with a brief comparison only when it helps.\n"
                    + "The separation between problem setup and motion-revealed task focus is important. For example, first place the points, lines, paths, or shapes, then drag the variable point or sweep the parameter to observe how the target quantity changes.\n"
                    + "Motion is preferred for solving intuition, but not mandatory for every node. Labels, formulas, narration, and static annotations may summarize, name, or explain facts that are already guaranteed by the figure. Do not add redundant nodes just to animate proof details.\n"
                    + "Avoid separate nodes for obvious geometric facts, unnecessary folding/flashing/dragging/repeated measurement, direct consequences of a construction, near-duplicate insights, or motion that increases node count without increasing understanding.\n"
                    + "If a relationship is guaranteed directly by a construction, such as equal distances from reflecting a point across a line, mention it inside the construction or key transformation node instead of creating a separate animated proof node. Only split out such a relationship when it is itself the learner's main conceptual obstacle.\n"
                    + "For shortest-path, extremum, locus, angle-change, or area-change problems, prefer dragging, sweeping, or dynamic comparison to reveal how the target quantity changes. For auxiliary-line, reflection, rotation, translation, or dissection methods, make the reason for the construction as visual as practical, but do not split every immediate consequence into its own proof animation.\n"
                    + "Use node types from: problem, observation, construction, derivation, conclusion.\n"
                    + "The start must be the hook or problem-framing node at depth 0, and later beats should progress toward the final conclusion.\n"
                    + "The graph should be compact, acyclic, and easy to present in topological order.\n"
                    + "Node rules:\n"
                    + "1. Each node should be one clear solving beat with one main takeaway.\n"
                    + "2. In `step`, prefer describing the visual setup, operation, change, or key construction the learner sees; if a beat is only a necessary naming, summary, or conclusion, state it clearly without forcing animation.\n"
                    + "3. Setting up the problem situation and revealing the task target through motion should be separate beats when useful; this helps the learner first understand the problem, then understand what is being optimized, proven, or found.\n"
                    + "4. The key insight or transformation should appear explicitly, shown through construction, transformation, overlay, dynamic comparison, or brief explanation.\n"
                    + "5. Obvious relationships, direct consequences of a construction, and simple equality substitutions should not become their own nodes; fold them into the relevant construction or derivation node.\n"
                    + "6. Do not bundle genuinely different solving moves into one node, but also do not over-split a natural continuous small inference into multiple nodes.\n"
                    + "7. Every node must stay directly relevant to the final solving goal.\n"
                    + "8. Add a final comparison beat only when it helps confirm optimality or the conclusion; do not add one for formal completeness.\n\n"
                    + "Edge rules:\n"
                    + "1. Add an edge only for truly necessary prerequisites.\n"
                    + "2. Avoid synonyms, near-duplicates, and parent-child duplication across nodes.\n"
                    + "3. Include prerequisite observations, failed attempts, or misconceptions only when they are needed to make the key insight understandable.\n\n"
                    + SystemPrompts.ASCII_TEXT_RULES
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"start_id\": \"string, id of the first solving beat\",\n"
                    + "  \"nodes\": [\n"
                    + "    {\"id\": \"string, unique node id\", \"step\": \"string, one presentation-ready solving beat\", \"reason\": \"string, why this beat matters in the solution flow\", \"node_type\": \"string, one of problem|observation|construction|derivation|conclusion\", \"min_depth\": \"integer, minimum distance from the start beat\"}\n"
                    + "  ],\n"
                    + "  \"next_edges\": {\"node_id\": [\"direct_next_node_id\"]},\n"
                    + "  \"teaching_order\": [\"node_id_1\", \"node_id_2\", \"...\"]\n"
                    + "}\n\n"
                    + "`teaching_order` is the intended presentation sequence. It must list every node id exactly once, respecting prerequisite dependencies (a node appears after all its prerequisites).\n"
                    + "The edge direction: node -> direct next beats that should follow it.\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private ExplorationPrompts() {}

    public static String buildInputModeRulesPrompt() {
        return SystemPrompts.buildRulesSection(INPUT_MODE_CLASSIFIER_SYSTEM);
    }

    public static String buildInputModeFixedContextPrompt() {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Input mode classification",
                "User-provided math input",
                "Decide whether this input should follow the concept-explanation workflow or the problem-solving workflow.",
                (String) null
        ));
    }

    public static String buildConceptGraphRulesPrompt() {
        return SystemPrompts.buildRulesSection(CONCEPT_GRAPH_SYSTEM);
    }

    public static String buildConceptGraphFixedContextPrompt(String targetDescription) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Concept teaching-graph planning",
                "Concept explanation workflow target",
                targetDescription,
                (String) null
        ));
    }

    public static String buildProblemGraphRulesPrompt() {
        return SystemPrompts.buildRulesSection(PROBLEM_GRAPH_SYSTEM);
    }

    public static String buildProblemGraphFixedContextPrompt(String targetDescription) {
        return SystemPrompts.buildFixedContextSection(SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Problem solution-step graph planning",
                "Problem-solving workflow target",
                targetDescription,
                (String) null
        ));
    }

}
