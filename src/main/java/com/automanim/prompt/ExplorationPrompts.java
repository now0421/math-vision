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
                    + "\n"
                    + "Return only structured output with `is_foundation` and optional `reason`.";

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
                    + "\n"
                    + "Each prerequisite must have `step` and `reason`.";

    private static final String INPUT_MODE_CLASSIFIER_SYSTEM =
            "You are a routing classifier for a math teaching-animation workflow.\n"
                    + "Choose `problem` for a concrete question, proof, optimization, or exercise to solve.\n"
                    + "Choose `concept` for a topic, theorem, formula, or idea to explain.\n"
                    + "Return only structured output with `input_mode` and optional `reason`.";

    private static final String PROBLEM_STEP_GRAPH_SYSTEM =
            "Plan animation-ready teaching beats for a middle-school math problem.\n"
                    + "Build a compact dependency graph whose nodes are major, visually teachable solving beats.\n"
                    + "Each node should feel close to one scene or one major reveal.\n"
                    + "Use node types from: problem, observation, construction, derivation, conclusion.\n"
                    + "The root must be the final conclusion node at depth 0.\n"
                    + "Prefer 4 to 7 strong beats unless the problem truly needs more.\n"
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
