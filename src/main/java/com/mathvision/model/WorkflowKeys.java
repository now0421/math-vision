package com.mathvision.model;

/**
 * Shared context key constants used by workflow nodes to read and write
 * data from the PocketFlow shared context map.
 *
 * Using constants avoids magic strings scattered across node classes
 * and provides a single place to see the full workflow data contract.
 */
public final class WorkflowKeys {

    private WorkflowKeys() {}

    // ---- Configuration (set before workflow runs) ----
    public static final String CONFIG = "config";
    public static final String AI_CLIENT = "aiClient";
    public static final String OUTPUT_DIR = "outputDir";

    // ---- Stage 0: Problem normalization ----
    public static final String PROBLEM_SOURCE = "problemSource";
    public static final String PROBLEM_BUNDLE = "problemBundle";
    public static final String PROBLEM_NORMALIZATION_API_CALLS = "problemNormalizationApiCalls";

    // ---- Stage 1: Exploration output ----
    public static final String KNOWLEDGE_GRAPH = "knowledgeGraph";
    public static final String EXPLORATION_API_CALLS = "explorationApiCalls";
    public static final String RESOLVED_INPUT_MODE = "resolvedInputMode";

    // ---- Stage 2: Enrichment and visual-design output ----
    public static final String ENRICHED_TREE = "enrichedTree";
    public static final String NARRATIVE = "narrative";
    public static final String ENRICHMENT_TOOL_CALLS = "enrichmentToolCalls";

    // ---- Stage 5: Code generation output ----
    public static final String CODE_RESULT = "codeResult";

    // ---- Shared code-fix routing state ----
    public static final String CODE_FIX_REQUEST = "codeFixRequest";
    public static final String CODE_FIX_RESULT = "codeFixResult";
    public static final String CODE_FIX_TRACE = "codeFixTrace";
    public static final String CODE_GENERATION_FIX_STATE = "codeGenerationFixState";
    public static final String CODE_EVALUATION_FIX_STATE = "codeEvaluationFixState";
    public static final String CODE_EVALUATION_HISTORY = "codeEvaluationHistory";
    public static final String RENDER_RETRY_STATE = "renderRetryState";
    public static final String SCENE_EVALUATION_RETRY_STATE = "sceneEvaluationRetryState";

    // ---- Stage 6: Code evaluation output ----
    public static final String CODE_EVALUATION_RESULT = "codeEvaluationResult";

    // ---- Stage 7: Render output ----
    public static final String RENDER_RESULT = "renderResult";
    public static final String RENDER_EVER_SUCCEEDED = "renderEverSucceeded";

    // ---- Stage 8: Scene evaluation output ----
    public static final String SCENE_EVALUATION_RESULT = "sceneEvaluationResult";

    // ---- Metrics ----
    public static final String STAGE_TIMES = "stageTimes";
}
