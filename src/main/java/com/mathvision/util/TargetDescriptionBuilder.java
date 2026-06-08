package com.mathvision.util;

import com.mathvision.config.ModelConfig;
import com.mathvision.model.KnowledgeGraph;
import com.mathvision.model.KnowledgeNode;
import com.mathvision.model.ProblemBundle;

import java.util.List;

/**
 * Builds workflow target descriptions and compact node context shared across stages.
 */
public final class TargetDescriptionBuilder {

    private static final int MAX_REASON_LENGTH = 200;

    private TargetDescriptionBuilder() {}

    /**
     * Builds a workflow target description from the authoritative ProblemBundle.
     */
    public static String build(ProblemBundle bundle, KnowledgeGraph graph, KnowledgeNode currentNode) {
        StringBuilder sb = new StringBuilder();

        if (ProblemBundleContextBuilder.isProblemMode(bundle)
                || (bundle == null && graph != null && graph.isProblemMode())) {
            sb.append("This is a problem-solving workflow. The target is the math problem described by the ProblemBundle.");
        } else {
            sb.append("The target is the math concept described by the ProblemBundle.");
        }

        if (currentNode != null) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("Current step: ").append(currentNode.getStep());
            if (currentNode.getReason() != null && !currentNode.getReason().isBlank()) {
                String reason = currentNode.getReason().trim();
                if (reason.length() > MAX_REASON_LENGTH) {
                    reason = reason.substring(0, MAX_REASON_LENGTH) + "...";
                }
                sb.append("\nWhy this step matters: ").append(reason);
            }
        }

        return sb.toString().trim();
    }

    public static String workflowTargetDescription(String legacyTargetConcept,
                                                   String terminalConcept,
                                                   String terminalDescription,
                                                   boolean problemMode,
                                                   String outputTarget) {
        ProblemBundle bundle = ProblemBundleContextBuilder.legacyBundle(legacyTargetConcept);
        bundle.setInputMode(problemMode
                ? com.mathvision.config.WorkflowConfig.INPUT_MODE_PROBLEM
                : com.mathvision.config.WorkflowConfig.INPUT_MODE_CONCEPT);
        return ProblemBundleContextBuilder.workflowTargetDescription(
                bundle, terminalConcept, terminalDescription, outputTarget);
    }

    /**
     * Builds a compact problem solution chain summary for prompts.
     */
    public static String buildSolutionChain(KnowledgeGraph graph, KnowledgeNode currentStep) {
        if (graph == null || !graph.isProblemMode()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Solution step chain:\n");

        List<KnowledgeNode> ordered = graph.teachingOrderNodes();
        int stepNumber = 1;
        int currentStepNumber = -1;

        for (KnowledgeNode node : ordered) {
            String marker = "";
            if (currentStep != null && node.getId().equals(currentStep.getId())) {
                marker = " <-- current";
                currentStepNumber = stepNumber;
            }

            sb.append(stepNumber).append(". ").append(node.getStep());

            String nodeType = node.getNodeType();
            if (nodeType != null && !nodeType.isBlank()
                    && !KnowledgeNode.NODE_TYPE_CONCEPT.equals(nodeType)) {
                sb.append(" [").append(nodeType).append("]");
            }

            sb.append(marker).append("\n");

            if (node.getReason() != null && !node.getReason().isBlank()) {
                String reason = node.getReason().trim();
                if (reason.length() > MAX_REASON_LENGTH) {
                    reason = reason.substring(0, MAX_REASON_LENGTH) + "...";
                }
                sb.append("   -> ").append(reason).append("\n");
            }

            stepNumber++;
        }

        if (currentStepNumber > 0) {
            sb.append("\nCurrently processing step ").append(currentStepNumber)
                    .append(" of ").append(ordered.size()).append(".");
        }

        return sb.toString().trim();
    }

    /**
     * Builds the default max input tokens from config or fallback.
     */
    public static int resolveMaxInputTokens(com.mathvision.config.WorkflowConfig config) {
        if (config != null && config.getModelConfig() != null) {
            return config.getModelConfig().getMaxInputTokens();
        }
        return ModelConfig.DEFAULT_MAX_INPUT_TOKENS;
    }
}
