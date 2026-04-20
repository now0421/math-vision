package com.mathvision;

import com.mathvision.node.CodeGenerationNode;
import com.mathvision.node.CodeFixNode;
import com.mathvision.node.ExplorationNode;
import com.mathvision.node.MathEnrichmentNode;
import com.mathvision.node.StoryboardValidationNode;
import com.mathvision.node.RenderNode;
import com.mathvision.node.SceneEvaluationNode;
import com.mathvision.node.VisualDesignNode;
import com.mathvision.node.CodeEvaluationNode;
import com.mathvision.model.WorkflowActions;
import io.github.the_pocket.PocketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the workflow with a shared routed code-fix node:
 *
 *   ExplorationNode -> MathEnrichmentNode -> VisualDesignNode
 *       -> StoryboardValidationNode -> CodeGenerationNode -> CodeEvaluationNode -> RenderNode
 *                          ^                ^                   ^
 *                          |                |                   |
 *                          +------ CodeFixNode <---------------+
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
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();
        RenderNode render = new RenderNode();
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        codeGen.next(codeEvaluation);
        codeGen.next(codeFix, WorkflowActions.FIX_CODE);
        codeEvaluation.next(render);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        render.next(sceneEvaluation);
        render.next(codeFix, WorkflowActions.FIX_CODE);
        sceneEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeGen, WorkflowActions.RETRY_CODE_GENERATION);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);
        codeFix.next(render, WorkflowActions.RETRY_RENDER);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Workflow assembled: Exploration -> MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation -> Render -> SceneEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow that skips rendering but still runs code evaluation.
     */
    public static PocketFlow.Flow<?> createWithoutRender() {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        codeGen.next(codeEvaluation);
        codeGen.next(codeFix, WorkflowActions.FIX_CODE);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeGen, WorkflowActions.RETRY_CODE_GENERATION);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);

        log.info("Workflow assembled (no render): Exploration -> MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 1 (skips stage 0 exploration).
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraph() {
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();
        RenderNode render = new RenderNode();
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();

        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        codeGen.next(codeEvaluation);
        codeGen.next(codeFix, WorkflowActions.FIX_CODE);
        codeEvaluation.next(render);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        render.next(sceneEvaluation);
        render.next(codeFix, WorkflowActions.FIX_CODE);
        sceneEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeGen, WorkflowActions.RETRY_CODE_GENERATION);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);
        codeFix.next(render, WorkflowActions.RETRY_RENDER);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(mathEnrich);

        log.info("Workflow assembled (from graph): MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation -> Render -> SceneEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 1, without rendering.
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraphWithoutRender() {
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = new CodeGenerationNode();
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        codeGen.next(codeEvaluation);
        codeGen.next(codeFix, WorkflowActions.FIX_CODE);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeGen, WorkflowActions.RETRY_CODE_GENERATION);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(mathEnrich);

        log.info("Workflow assembled (from graph, no render): MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 3 (skips exploration through code generation).
     * Use when Manim code has been loaded manually via --from-code.
     */
    public static PocketFlow.Flow<?> createFromCode() {
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();
        RenderNode render = new RenderNode();
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();

        codeEvaluation.next(render);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        render.next(sceneEvaluation);
        render.next(codeFix, WorkflowActions.FIX_CODE);
        sceneEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);
        codeFix.next(render, WorkflowActions.RETRY_RENDER);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(codeEvaluation);

        log.info("Workflow assembled (from code): CodeEvaluation -> Render -> SceneEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 3, without rendering.
     * Use when Manim code has been loaded manually via --from-code.
     */
    public static PocketFlow.Flow<?> createFromCodeWithoutRender() {
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(codeEvaluation);

        log.info("Workflow assembled (from code, no render): CodeEvaluation with routed CodeFixNode");
        return flow;
    }
}
