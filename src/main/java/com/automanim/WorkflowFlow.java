package com.automanim;

import com.automanim.node.CodeGenerationNode;
import com.automanim.node.ExplorationNode;
import com.automanim.node.MathEnrichmentNode;
import com.automanim.node.NarrativeNode;
import com.automanim.node.RenderNode;
import com.automanim.node.VisualDesignNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the full 6-node linear workflow:
 *
 *   ExplorationNode -> MathEnrichmentNode -> VisualDesignNode
 *       -> NarrativeNode -> CodeGenerationNode -> RenderNode
 *
 * Each node communicates via the shared context map using WorkflowKeys constants.
 */
public class WorkflowFlow {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFlow.class);

    /**
     * Creates the full workflow with all 6 stages wired together.
     */
    public static PocketFlow.Flow<?> create() {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        RenderNode render = new RenderNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);
        codeGen.next(render);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Workflow assembled: Exploration -> MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration -> Render");
        return flow;
    }

    /**
     * Creates a workflow that skips rendering (exploration -> enrichment -> code only).
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

        log.info("Workflow assembled (no render): Exploration -> MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration");
        return flow;
    }
}
