package com.mathvision;

import com.mathvision.config.WorkflowConfig;
import com.mathvision.node.CodeGenerationNode;
import com.mathvision.node.CodeFixNode;
import com.mathvision.node.ExplorationNode;
import com.mathvision.node.MathEnrichmentNode;
import com.mathvision.node.ProblemNormalizationNode;
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
        return create(null);
    }

    /**
     * Creates the full workflow with all stages wired together.
     */
    public static PocketFlow.Flow<?> create(WorkflowConfig config) {
        ProblemNormalizationNode normalization = new ProblemNormalizationNode();
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();
        RenderNode render = new RenderNode();
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();

        normalization.next(exploration);
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

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(normalization);

        log.info("Workflow assembled: ProblemNormalization -> Exploration -> MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation -> Render -> SceneEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow that skips rendering but still runs code evaluation.
     */
    public static PocketFlow.Flow<?> createWithoutRender() {
        return createWithoutRender(null);
    }

    /**
     * Creates a workflow that skips rendering but still runs code evaluation.
     */
    public static PocketFlow.Flow<?> createWithoutRender(WorkflowConfig config) {
        ProblemNormalizationNode normalization = new ProblemNormalizationNode();
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        normalization.next(exploration);
        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        codeGen.next(codeEvaluation);
        codeGen.next(codeFix, WorkflowActions.FIX_CODE);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeGen, WorkflowActions.RETRY_CODE_GENERATION);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(normalization);

        log.info("Workflow assembled (no render): ProblemNormalization -> Exploration -> MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation with routed CodeFixNode");
        return flow;
    }

    /**
     * Creates a workflow starting from stage 2 (skips stages 0-1).
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraph() {
        return createFromGraph(null);
    }

    /**
     * Creates a workflow starting from stage 2 (skips stages 0-1).
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraph(WorkflowConfig config) {
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
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
     * Creates a workflow starting from stage 2, without rendering.
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraphWithoutRender() {
        return createFromGraphWithoutRender(null);
    }

    /**
     * Creates a workflow starting from stage 2, without rendering.
     * Use when the knowledge graph has been loaded manually via --from-graph.
     */
    public static PocketFlow.Flow<?> createFromGraphWithoutRender(WorkflowConfig config) {
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
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
     * Creates a workflow starting from stage 6 (skips stages 0-5).
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
     * Creates a workflow starting from stage 6, without rendering.
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

    /**
     * Creates a workflow that starts immediately after an already completed
     * stage. The caller is responsible for loading the required upstream
     * artifacts into the shared context before running the flow.
     */
    public static PocketFlow.Flow<?> createAfterStage(int completedStage, WorkflowConfig config) {
        boolean renderEnabled = config == null || config.isRenderEnabled();
        switch (completedStage) {
            case 0:
                return createFromProblemBundle(config, renderEnabled);
            case 1:
                return renderEnabled ? createFromGraph(config) : createFromGraphWithoutRender(config);
            case 2:
                return createFromEnrichedGraph(config, renderEnabled);
            case 3:
                return createFromNarrative(config, renderEnabled);
            case 4:
                return createFromValidatedStoryboard(config, renderEnabled);
            case 5:
                return renderEnabled ? createFromCode() : createFromCodeWithoutRender();
            case 6:
                if (!renderEnabled) {
                    throw new IllegalArgumentException(
                            "No downstream stage remains after stage 6 when rendering is disabled");
                }
                return createFromCodeEvaluation();
            case 7:
                return createFromRenderResult();
            default:
                throw new IllegalArgumentException("Cannot resume after stage " + completedStage
                        + "; expected a completed stage from 0 through 7");
        }
    }

    /**
     * Creates a workflow that only runs the ProblemNormalization stage (Stage 0).
     */
    public static PocketFlow.Flow<?> createProblemNormalizationOnly() {
        ProblemNormalizationNode normalization = new ProblemNormalizationNode();
        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(normalization);
        log.info("Workflow assembled (normalization only): ProblemNormalization stage only");
        return flow;
    }

    /**
     * Creates a workflow that runs through the Exploration stage (Stage 1).
     * Stops after generating the knowledge graph.
     */
    public static PocketFlow.Flow<?> createExplorationOnly() {
        ProblemNormalizationNode normalization = new ProblemNormalizationNode();
        ExplorationNode exploration = new ExplorationNode();
        normalization.next(exploration);
        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(normalization);
        log.info("Workflow assembled (exploration only): ProblemNormalization -> Exploration stage only");
        return flow;
    }

    /**
     * Creates a workflow that runs from Exploration to StoryboardValidation (Stages 0-3).
     * Stops after storyboard validation.
     */
    public static PocketFlow.Flow<?> createToStoryboardValidation() {
        ProblemNormalizationNode normalization = new ProblemNormalizationNode();
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();

        normalization.next(exploration);
        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(normalization);
        log.info("Workflow assembled (to storyboard validation): ProblemNormalization -> Exploration -> MathEnrichment -> VisualDesign -> StoryboardValidation");
        return flow;
    }

    /**
     * Creates a workflow that runs from Exploration to VisualDesign (Stages 0-2).
     * Stops after visual design.
     */
    public static PocketFlow.Flow<?> createToVisualDesign() {
        ProblemNormalizationNode normalization = new ProblemNormalizationNode();
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();

        normalization.next(exploration);
        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(normalization);
        log.info("Workflow assembled (to visual design): ProblemNormalization -> Exploration -> MathEnrichment -> VisualDesign");
        return flow;
    }

    private static CodeGenerationNode createCodeGenerationNode(WorkflowConfig config) {
        int maxRetries = config != null ? config.getCodeGenMaxRetries() : 2;
        return new CodeGenerationNode(maxRetries);
    }

    private static PocketFlow.Flow<?> createFromProblemBundle(WorkflowConfig config, boolean renderEnabled) {
        ExplorationNode exploration = new ExplorationNode();
        MathEnrichmentNode mathEnrich = new MathEnrichmentNode();
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        exploration.next(mathEnrich);
        mathEnrich.next(visualDesign);
        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        wireCodeGenerationToEvaluation(codeGen, codeEvaluation, codeFix);
        if (renderEnabled) {
            wireEvaluationToRender(codeEvaluation, codeFix);
        } else {
            wireEvaluationWithoutRender(codeEvaluation, codeFix);
        }

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(exploration);
        log.info("Workflow assembled (after stage 0): Exploration -> MathEnrichment -> VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation{}",
                renderEnabled ? " -> Render -> SceneEvaluation with routed CodeFixNode" : " with routed CodeFixNode");
        return flow;
    }

    private static PocketFlow.Flow<?> createFromEnrichedGraph(WorkflowConfig config, boolean renderEnabled) {
        VisualDesignNode visualDesign = new VisualDesignNode();
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        visualDesign.next(storyboardValidation);
        storyboardValidation.next(codeGen);
        wireCodeGenerationToEvaluation(codeGen, codeEvaluation, codeFix);
        if (renderEnabled) {
            wireEvaluationToRender(codeEvaluation, codeFix);
        } else {
            wireEvaluationWithoutRender(codeEvaluation, codeFix);
        }

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(visualDesign);
        log.info("Workflow assembled (after stage 2): VisualDesign -> StoryboardValidation -> CodeGeneration -> CodeEvaluation{}",
                renderEnabled ? " -> Render -> SceneEvaluation with routed CodeFixNode" : " with routed CodeFixNode");
        return flow;
    }

    private static PocketFlow.Flow<?> createFromNarrative(WorkflowConfig config, boolean renderEnabled) {
        StoryboardValidationNode storyboardValidation = new StoryboardValidationNode();
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        storyboardValidation.next(codeGen);
        wireCodeGenerationToEvaluation(codeGen, codeEvaluation, codeFix);
        if (renderEnabled) {
            wireEvaluationToRender(codeEvaluation, codeFix);
        } else {
            wireEvaluationWithoutRender(codeEvaluation, codeFix);
        }

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(storyboardValidation);
        log.info("Workflow assembled (after stage 3): StoryboardValidation -> CodeGeneration -> CodeEvaluation{}",
                renderEnabled ? " -> Render -> SceneEvaluation with routed CodeFixNode" : " with routed CodeFixNode");
        return flow;
    }

    private static PocketFlow.Flow<?> createFromValidatedStoryboard(WorkflowConfig config, boolean renderEnabled) {
        CodeGenerationNode codeGen = createCodeGenerationNode(config);
        CodeEvaluationNode codeEvaluation = new CodeEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        wireCodeGenerationToEvaluation(codeGen, codeEvaluation, codeFix);
        if (renderEnabled) {
            wireEvaluationToRender(codeEvaluation, codeFix);
        } else {
            wireEvaluationWithoutRender(codeEvaluation, codeFix);
        }

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(codeGen);
        log.info("Workflow assembled (after stage 4): CodeGeneration -> CodeEvaluation{}",
                renderEnabled ? " -> Render -> SceneEvaluation with routed CodeFixNode" : " with routed CodeFixNode");
        return flow;
    }

    private static PocketFlow.Flow<?> createFromCodeEvaluation() {
        RenderNode render = new RenderNode();
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();

        render.next(sceneEvaluation);
        render.next(codeFix, WorkflowActions.FIX_CODE);
        sceneEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(render, WorkflowActions.RETRY_RENDER);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(render);
        log.info("Workflow assembled (after stage 6): Render -> SceneEvaluation with routed CodeFixNode");
        return flow;
    }

    private static PocketFlow.Flow<?> createFromRenderResult() {
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();
        CodeFixNode codeFix = new CodeFixNode();
        RenderNode render = new RenderNode();

        sceneEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(render, WorkflowActions.RETRY_RENDER);
        render.next(sceneEvaluation);
        render.next(codeFix, WorkflowActions.FIX_CODE);

        PocketFlow.Flow<?> flow = new PocketFlow.Flow<>(sceneEvaluation);
        log.info("Workflow assembled (after stage 7): SceneEvaluation with routed CodeFixNode");
        return flow;
    }

    private static void wireCodeGenerationToEvaluation(CodeGenerationNode codeGen,
                                                       CodeEvaluationNode codeEvaluation,
                                                       CodeFixNode codeFix) {
        codeGen.next(codeEvaluation);
        codeGen.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(codeGen, WorkflowActions.RETRY_CODE_GENERATION);
    }

    private static void wireEvaluationToRender(CodeEvaluationNode codeEvaluation, CodeFixNode codeFix) {
        RenderNode render = new RenderNode();
        SceneEvaluationNode sceneEvaluation = new SceneEvaluationNode();

        codeEvaluation.next(render);
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        render.next(sceneEvaluation);
        render.next(codeFix, WorkflowActions.FIX_CODE);
        sceneEvaluation.next(codeFix, WorkflowActions.FIX_CODE);

        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);
        codeFix.next(render, WorkflowActions.RETRY_RENDER);
    }

    private static void wireEvaluationWithoutRender(CodeEvaluationNode codeEvaluation, CodeFixNode codeFix) {
        codeEvaluation.next(codeFix, WorkflowActions.FIX_CODE);
        codeFix.next(codeEvaluation, WorkflowActions.RETRY_CODE_EVALUATION);
    }
}
