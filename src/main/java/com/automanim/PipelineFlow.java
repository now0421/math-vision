package com.automanim;

import com.automanim.node.*;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the full 6-node linear pipeline:
 *
 *   ExplorationNode → MathEnrichmentNode → VisualDesignNode
 *       → NarrativeNode → CodeGenerationNode → RenderNode
 *
 * Each node communicates via the shared context Map using PipelineKeys constants.
 */
public class PipelineFlow {

    private static final Logger log = LoggerFactory.getLogger(PipelineFlow.class);

    /**
     * Creates the full pipeline flow with all 6 stages wired together.
     */
    public static PocketFlow.Flow<?> create() {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        RenderNode render = new RenderNode();

        // Wire linear chain: each node's default action → next node
        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);
        codeGen.next(render);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Pipeline assembled: Exploration → MathEnrichment → VisualDesign → Narrative → CodeGeneration → Render");
        return flow;
    }

    /**
     * Creates a pipeline that skips rendering (exploration → enrichment → code only).
     */
    public static PocketFlow.Flow<?> createWithoutRender() {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Pipeline assembled (no render): Exploration → MathEnrichment → VisualDesign → Narrative → CodeGeneration");
        return flow;
    }
}
