package com.automanim;

import com.automanim.node.CodeGenerationNode;
import com.automanim.node.ExplorationNode;
import com.automanim.node.MathEnrichmentNode;
import com.automanim.node.NarrativeNode;
import com.automanim.node.RenderNode;
import com.automanim.node.VisualDesignNode;
import com.automanim.node.CodeEvaluationNode;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the full 7-node linear workflow:
 *
 *   ExplorationNode -> MathEnrichmentNode -> VisualDesignNode
 *       -> NarrativeNode -> CodeGenerationNode -> CodeEvaluationNode -> RenderNode
 *
 * Each node communicates via the shared context map using WorkflowKeys constants.
 */
public class WorkflowFlow {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFlow.class);

    /**
     * Creates the full workflow with all stages wired together.
     */
    public static PocketFlow.Flow<?> create() {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        RenderNode render = new RenderNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);
        codeGen.next(codeEvaluation);
        codeEvaluation.next(render);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Workflow assembled: Exploration -> MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration -> CodeEvaluation -> Render");
        return flow;
    }

    /**
     * Creates a workflow that skips rendering but still runs code evaluation.
     */
    public static PocketFlow.Flow<?> createWithoutRender() {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);
        codeGen.next(codeEvaluation);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Workflow assembled (no render): Exploration -> MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration -> CodeEvaluation");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 1 (skips stage 0 exploration).
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraph() {
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        RenderNode render = new RenderNode();

        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);
        codeGen.next(codeEvaluation);
        codeEvaluation.next(render);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(mathEnrich);

        log.info("Workflow assembled (from graph): MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration -> CodeEvaluation -> Render");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 1, without rendering.
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraphWithoutRender() {
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        NarrativeNode narrative = new NarrativeNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();

        mathEnrich.next(visualDesign);
        visualDesign.next(narrative);
        narrative.next(codeGen);
        codeGen.next(codeEvaluation);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(mathEnrich);

        log.info("Workflow assembled (from graph, no render): MathEnrichment -> VisualDesign -> Narrative -> CodeGeneration -> CodeEvaluation");
        return flow;
    }
}
