package com.automanim.model;

/**
 * Shared context key constants used by pipeline nodes to read/write
 * data from the PocketFlow shared context map.
 *
 * Using constants avoids magic strings scattered across node classes
 * and provides a single place to see the full pipeline data contract.
 */
public final class PipelineKeys {

    private PipelineKeys() {}

    // ---- Configuration (set before pipeline runs) ----
    public static final String CONFIG = "config";
    public static final String AI_CLIENT = "aiClient";
    public static final String OUTPUT_DIR = "outputDir";
    public static final String CONCEPT = "concept";

    // ---- Stage 0: Exploration output ----
    public static final String KNOWLEDGE_TREE = "knowledgeTree";
    public static final String EXPLORATION_API_CALLS = "explorationApiCalls";

    // ---- Stage 1: Enrichment output ----
    public static final String ENRICHED_TREE = "enrichedTree";
    public static final String NARRATIVE = "narrative";
    public static final String ENRICHMENT_TOOL_CALLS = "enrichmentToolCalls";

    // ---- Stage 2: Code generation output ----
    public static final String CODE_RESULT = "codeResult";

    // ---- Stage 3: Render output ----
    public static final String RENDER_RESULT = "renderResult";

    // ---- Metrics ----
    public static final String STAGE_TIMES = "stageTimes";
}
