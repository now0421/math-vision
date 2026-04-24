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
                    + "Build a learner-facing dependency graph whose nodes are major, visually teachable explanation beats.\n"
                    + "Prefer a compact teaching path over exhaustive prerequisite coverage.\n"
                    + "Use node types from: concept, observation, construction, derivation, conclusion.\n"
                    + "The start must be the first entry beat at depth 0, and later beats should progress toward the final conclusion.\n"
                    + "Target about 5 to 9 strong beats unless the concept truly needs fewer.\n"
                    + "Keep the graph compact, acyclic, and easy to teach in topological order.\n"
                    + "`is_foundation` is metadata only and does not control further expansion.\n\n"
                    + "Node rules:\n"
                    + "1. Each node must be one atomic teaching beat with a concrete visual focus and one main takeaway.\n"
                    + "2. A valid beat may be an observation, a failed attempt, a contrast, or a key insight, not only a raw derivation step.\n"
                    + "3. Do not bundle multiple hidden reasoning moves into one node.\n"
                    + "4. Every node must stay clearly relevant to the final teaching goal.\n\n"
                    + "Edge rules:\n"
                    + "1. Add an edge only for truly necessary prerequisites, not helpful background.\n"
                    + "2. Avoid synonyms, near-duplicates, and parent-child duplication across nodes.\n"
                    + "3. Include prerequisite observations or misconceptions when they are needed to make the later insight feel earned.\n\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"start_id\": \"string, id of the first teaching beat\",\n"
                    + "  \"nodes\": [\n"
                    + "    {\"id\": \"string, unique node id\", \"step\": \"string, one presentation-ready teaching beat\", \"reason\": \"string, why this beat matters in the explanation flow\", \"node_type\": \"string, one of concept|observation|construction|derivation|conclusion\", \"min_depth\": \"integer, minimum distance from the start beat\", \"is_foundation\": \"boolean, metadata annotation for whether the beat is already elementary enough\"}\n"
                    + "  ],\n"
                    + "  \"next_edges\": {\"node_id\": [\"direct_next_node_id\"]},\n"
                    + "  \"teaching_order\": [\"node_id_1\", \"node_id_2\", \"...\"]\n"
                    + "}\n\n"
                    + "`teaching_order` is the intended presentation sequence. It must list every node id exactly once, respecting prerequisite dependencies (a node appears after all its prerequisites).\n"
                    + "The edge direction: node -> direct next beats that should follow it.\n"
                    + SystemPrompts.TOOL_CALL_HINT
                    + SystemPrompts.JSON_ONLY_OUTPUT;

    private static final String PROBLEM_GRAPH_SYSTEM =
            "Plan presentation-ready teaching beats for a middle-school math problem.\n"
                    + "Build a compact dependency graph whose nodes are major, visually teachable solving beats.\n"
                    + "Each node should feel close to one scene or one major reveal.\n"
                    + "Prefer a discovery arc or problem-solution arc over a dry list of algebraic manipulations.\n"
                    + "The graph should help the learner move from hook to observation to key insight to conclusion.\n"
                    + "Use `step` for what the beat does or shows, and `reason` for why that beat is needed before the next one.\n"
                    + "Make the key insight or transformation explicit in its own beat.\n"
                    + "Use node types from: problem, observation, construction, derivation, conclusion.\n"
                    + "The start must be the hook or problem-framing node at depth 0, and later beats should progress toward the final conclusion.\n"
                    + "Prefer 4 to 7 strong beats unless the problem truly needs more.\n"
                    + "The graph should be compact, acyclic, and easy to present in topological order.\n\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"start_id\": \"string, id of the first solving beat\",\n"
                    + "  \"nodes\": [\n"
                    + "    {\"id\": \"string, unique node id\", \"step\": \"string, one presentation-ready solving beat\", \"reason\": \"string, why this beat matters in the solution flow\", \"node_type\": \"string, one of problem|observation|construction|derivation|conclusion\", \"min_depth\": \"integer, minimum distance from the start beat\", \"is_foundation\": \"boolean, whether the beat is already elementary enough without further expansion\"}\n"
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

    public static String buildConceptGraphRulesPrompt(int maxDepth, int minDepth) {
        return SystemPrompts.buildRulesSection(CONCEPT_GRAPH_SYSTEM + depthBudgetInstruction(maxDepth, minDepth));
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

    public static String buildProblemGraphRulesPrompt(int maxDepth, int minDepth) {
        return SystemPrompts.buildRulesSection(PROBLEM_GRAPH_SYSTEM + depthBudgetInstruction(maxDepth, minDepth));
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

    private static String depthBudgetInstruction(int maxDepth, int minDepth) {
        return "Try to stay within the recommended maximum depth of " + Math.max(1, maxDepth)
                + " levels when possible. Additionally, try to make the graph at least " + Math.max(0, minDepth)
                + " levels deep when the teaching flow naturally supports it.\n";
    }
}
