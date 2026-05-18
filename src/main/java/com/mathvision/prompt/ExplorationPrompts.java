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
            "Plan a motion-driven teaching DAG for a middle-school math concept.\n"
                    + "Each node must be centered on a concrete visual action - moving, morphing, sweeping, constructing, or transforming elements - that lets the learner see the concept emerge, not just be told about it.\n"
                    + "Start by building a concrete visual situation where the concept naturally arises, then use a separate motion or manipulation beat to reveal the relationship, invariant, or pattern the concept captures.\n"
                    + "A later beat can name or formalize that visible idea; keep setup, motion reveal, and formal naming separate when more than one is needed.\n"
                    + "The motion IS the explanation: drag a point to discover a locus, sweep an angle to reveal a relationship, morph one shape into another to show equivalence, animate a construction step-by-step to build intuition.\n"
                    + "Prioritize movable visual elements over text: labels, formulas, and narration should name or summarize what the learner sees through motion, not carry the main beat by themselves.\n"
                    + "Avoid nodes that are purely algebraic declarations or static text with no driving visual action. Every beat should answer 'what moves and what does the learner see when it moves?'.\n"
                    + "Use node types from: concept, observation, construction, derivation, conclusion.\n"
                    + "The start must be the first entry beat at depth 0, and later beats should progress toward the final conclusion.\n"
                    + "Keep the graph compact, acyclic, and easy to teach in topological order.\n"
                    + "Node rules:\n"
                    + "1. Each node must be one atomic teaching beat driven by a specific visual action with one main takeaway.\n"
                    + "2. Describe the visual action in `step` (e.g. 'Slide point P along line l and watch the traced path form a parabola'), not just the conclusion (e.g. 'The locus is a parabola').\n"
                    + "3. Setting up the concept situation, revealing the concept's meaning through motion, and naming or formalizing the concept are separate atomic beats when more than one is needed; do not merge them into one node.\n"
                    + "4. A valid concept beat may be a motion-revealed observation, a dynamic contrast, or a key property discovered by animating a transformation - not a static definition or derivation step.\n"
                    + "5. Do not bundle multiple hidden reasoning moves into one node.\n"
                    + "6. Every node must stay clearly relevant to the final teaching goal.\n"
                    + "7. If comparing with other results at the end can better help the learner understand the conclusion, add a corresponding comparison beat with a visual action (e.g. overlay or morph).\n\n"
                    + "Edge rules:\n"
                    + "1. Add an edge only for truly necessary prerequisites, not helpful background.\n"
                    + "2. Avoid synonyms, near-duplicates, and parent-child duplication across nodes.\n"
                    + "3. Include prerequisite observations or misconceptions when they are needed to make the later insight feel earned - preferably through motion that exposes the gap.\n\n"
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
            "Plan motion-driven teaching beats for a middle-school math problem.\n"
                    + "Each node must be centered on a concrete visual action - moving, morphing, sweeping, constructing, or transforming elements - that lets the learner discover the solution path by watching geometry come alive, not just read a sequence of reasoning steps.\n"
                    + "Start by building the problem situation as a concrete visual setup, then use a separate motion or manipulation beat to reveal what quantity, relation, or target the problem is asking about.\n"
                    + "The motion IS the solving process: drag a point to discover a constraint, sweep a parameter to reveal an extremum, construct auxiliary elements one by one to uncover a hidden relationship, transform a figure to expose symmetry or equivalence.\n"
                    + "Prioritize movable problem quantities and visual elements over text: labels, formulas, and narration should name or summarize what the learner sees through motion, not carry the main beat by themselves.\n"
                    + "Avoid nodes that are purely algebraic manipulations or logical declarations with no driving visual action. Every beat should answer 'what moves and what does the learner see when it moves?'.\n"
                    + "The graph should help the learner move from concrete setup to motion-revealed task focus to animated key insight to visual conclusion - the solving process should feel like a guided discovery through movement, not a narrated proof.\n"
                    + "Use `step` for the visual action the learner watches (e.g. 'Reflect point P across line l and observe that the reflected path is always shorter'), and `reason` for why that motion must come before the next one.\n"
                    + "Make the key insight or transformation explicit in its own beat - shown through a decisive visual action, not just stated.\n"
                    + "If comparing with other results at the end can better help the learner understand the conclusion, add a corresponding comparison beat with a visual action (e.g. overlay or morph).\n"
                    + "Use node types from: problem, observation, construction, derivation, conclusion.\n"
                    + "The start must be the hook or problem-framing node at depth 0, and later beats should progress toward the final conclusion.\n"
                    + "The graph should be compact, acyclic, and easy to present in topological order.\n"
                    + "Node rules:\n"
                    + "1. Each node must be one atomic solving beat driven by a specific visual action with one main takeaway.\n"
                    + "2. Describe the visual action in `step` (e.g. 'Drag P along the river line and watch AP + PB change'), not just the conclusion (e.g. 'The shortest path uses reflection').\n"
                    + "3. Setting up the problem situation and revealing the question's target through motion are separate atomic beats when both are needed; do not merge them into one node.\n"
                    + "4. A valid beat may be a motion-revealed observation, a failed attempt shown through movement, a dynamic contrast, or a key insight discovered by animating a transformation - not a static derivation step.\n"
                    + "5. Do not bundle multiple hidden reasoning moves into one node.\n"
                    + "6. Every node must stay clearly relevant to the final solving goal.\n"
                    + "7. If comparing with other results at the end can better help the learner understand the conclusion, add a corresponding comparison beat with a visual action (e.g. overlay or morph).\n\n"
                    + "Edge rules:\n"
                    + "1. Add an edge only for truly necessary prerequisites, not helpful background.\n"
                    + "2. Avoid synonyms, near-duplicates, and parent-child duplication across nodes.\n"
                    + "3. Include prerequisite observations, failed attempts, or misconceptions when they are needed to make the later insight feel earned - preferably through motion that exposes the gap.\n\n"
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
