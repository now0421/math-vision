package com.automanim.prompt;

/**
 * Prompts for Stage 0: concept/problem exploration.
 */
public final class ExplorationPrompts {

    private static final String FOUNDATION_CHECK_SYSTEM =
            "Decide whether the current node is already small, concrete, and self-contained"
                    + " enough to serve as one animation-ready teaching beat for middle-school learners.\n"
                    + "\n"
                    + "Workflow context:\n"
                    + "- The DAG will be topologically traversed to produce a sequence of animated scenes.\n"
                    + "- Each node becomes one scene; foundation nodes must be truly self-explanatory.\n"
                    + "- Judge whether this node can support one clear animated teaching beat with a concrete focus.\n"
                    + "\n"
                    + "Rules:\n"
                    + "1. Junior-high-school math is the default foundation layer.\n"
                    + "2. The step must still be clearly relevant to the final teaching goal.\n"
                    + "3. If it bundles multiple hidden reasoning moves, answer no.\n"
                    + "4. If it requires advanced abstraction or significant prior knowledge, answer no.\n"
                    + "5. Bias toward yes when uncertain.\n"
                    + "6. Keep the reasoning short and decision-oriented.\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"is_foundation\": \"boolean, whether the node can already serve as one self-contained animation-ready teaching beat\",\n"
                    + "  \"reason\": \"string, brief justification for the decision\"\n"
                    + "}\n"
                    + "\n"
                    + "Example output:\n"
                    + "{\n"
                    + "  \"is_foundation\": true,\n"
                    + "  \"reason\": \"The step is already concrete, focused, and can be staged as one clear scene without extra hidden prerequisites\"\n"
                    + "}\n"
                    + "\n"
                    + "Set `is_foundation` to true only when the node can stand alone as one clear scene.\n"
                    + "If tools are available, call them.\n"
                    + "Return JSON only.";

    private static final String PREREQUISITES_SYSTEM =
            "Return the direct prerequisite teaching beats needed before the current node can"
                    + " be animated clearly for middle-school learners.\n"
                    + "\n"
                    + "Rules:\n"
                    + "1. Return only truly necessary prerequisites, not helpful background.\n"
                    + "2. Keep them directly relevant to the final teaching goal.\n"
                    + "3. Prefer concrete, visualizable beats over broad textbook topics.\n"
                    + "4. Each prerequisite should be one atomic teaching beat.\n"
                    + "5. Avoid synonyms, near-duplicates, parent-child duplication, and hidden bundles.\n"
                    + "6. Return at most 3 to 5 items ordered by necessity.\n"
                    + "7. Prefer prerequisites that are easy to visualize in animation.\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"prerequisites\": [\n"
                    + "    {\n"
                    + "      \"step\": \"string, short description of one direct prerequisite teaching beat\",\n"
                    + "      \"reason\": \"string, why this prerequisite is necessary before the current node\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n"
                    + "\n"
                    + "Example output:\n"
                    + "{\n"
                    + "  \"prerequisites\": [\n"
                    + "    {\n"
                    + "      \"step\": \"Recognize the relevant definition or theorem\",\n"
                    + "      \"reason\": \"The learner needs this idea before the current step will feel motivated and visually clear\"\n"
                    + "    },\n"
                    + "    {\n"
                    + "      \"step\": \"Identify the quantities or objects involved\",\n"
                    + "      \"reason\": \"This establishes the concrete entities that later animation actions will refer to\"\n"
                    + "    }\n"
                    + "  ]\n"
                    + "}\n"
                    + "\n"
                    + "Each prerequisite must have `step` and `reason`.\n"
                    + "If tools are available, call them.\n"
                    + "Return JSON only.";

    private static final String INPUT_MODE_CLASSIFIER_SYSTEM =
            "You are a routing classifier for a math teaching-animation workflow.\n"
                    + "Choose `problem` for a concrete question, proof, optimization, or exercise to solve.\n"
                    + "Choose `concept` for a topic, theorem, formula, or idea to explain.\n"
                    + "Prefer the most operational interpretation of the user's request.\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"input_mode\": \"string, either concept or problem\",\n"
                    + "  \"reason\": \"string, brief routing rationale\"\n"
                    + "}\n"
                    + "\n"
                    + "Example output:\n"
                    + "{\n"
                    + "  \"input_mode\": \"problem\",\n"
                    + "  \"reason\": \"The input asks for a concrete result to be solved rather than a general concept explanation\"\n"
                    + "}\n"
                    + "\n"
                    + "`input_mode` must be either `concept` or `problem`.\n"
                    + "If tools are available, call them.\n"
                    + "Return JSON only.";

    private static final String PROBLEM_STEP_GRAPH_SYSTEM =
            "Plan animation-ready teaching beats for a middle-school math problem.\n"
                    + "Build a compact dependency graph whose nodes are major, visually teachable solving beats.\n"
                    + "Each node should feel close to one scene or one major reveal.\n"
                    + "Use node types from: problem, observation, construction, derivation, conclusion.\n"
                    + "The root must be the final conclusion node at depth 0.\n"
                    + "Prefer 4 to 7 strong beats unless the problem truly needs more.\n"
                    + "The graph should be compact, acyclic, and easy to animate in topological order.\n"
                    + "\n"
                    + "Output format:\n"
                    + "Return a JSON object with this shape:\n"
                    + "{\n"
                    + "  \"root_id\": \"string, id of the final conclusion node\",\n"
                    + "  \"nodes\": [\n"
                    + "    {\n"
                    + "      \"id\": \"string, unique node id\",\n"
                    + "      \"step\": \"string, one animation-ready solving beat\",\n"
                    + "      \"reason\": \"string, why this beat matters in the solution flow\",\n"
                    + "      \"node_type\": \"string, one of problem|observation|construction|derivation|conclusion\",\n"
                    + "      \"min_depth\": \"integer, minimum distance from the final conclusion\",\n"
                    + "      \"is_foundation\": \"boolean, whether the beat is already elementary enough without further expansion\"\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"prerequisite_edges\": {\n"
                    + "    \"node_id\": [\"direct_dependency_node_id\"]\n"
                    + "  }\n"
                    + "}\n"
                    + "\n"
                    + "Example output:\n"
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

    private ExplorationPrompts() {}

    public static String foundationSystemPrompt(String targetTitle, String targetDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Foundation sufficiency check",
                targetTitle,
                targetDescription,
                false
        ) + FOUNDATION_CHECK_SYSTEM;
    }

    public static String prerequisiteSystemPrompt(String targetTitle, String targetDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Direct prerequisite extraction",
                targetTitle,
                targetDescription,
                false
        ) + PREREQUISITES_SYSTEM;
    }

    public static String inputModeSystemPrompt(String inputText) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Input mode classification",
                inputText,
                "Decide whether this input should follow the concept-explanation workflow or the problem-solving workflow.",
                false
        ) + INPUT_MODE_CLASSIFIER_SYSTEM;
    }

    public static String problemGraphSystemPrompt(String targetTitle, String targetDescription) {
        return SystemPrompts.buildWorkflowPrefix(
                "Stage 0 / Exploration",
                "Problem solution-step graph planning",
                targetTitle,
                targetDescription,
                false
        ) + PROBLEM_STEP_GRAPH_SYSTEM;
    }
}
